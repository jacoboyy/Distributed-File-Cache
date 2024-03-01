/*
 * @file:   Proxy.java
 * @author: Jacob Wang <tengdaw@andrew.cmu.edu>
 * Implementation of a proxy that sits between client and server and provides whole-file 
 * caching with an LRU replacement policy. Java RMI library is used for the RPC between 
 * proxy and client. The proxy adopts an AFS-1 like check-on-open cache consistency model 
 * which enforces one-copy-semantic at open-close session granularity. For concurrent write 
 * to the same file, the late write will win.
 */

import java.io.*;
import java.util.*;
import java.nio.file.*;
import java.rmi.NotBoundException;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.util.concurrent.ConcurrentHashMap;

class Proxy {
	
	private static String serverIP;			// server IP
	private static int serverPort;			// server port number
	private static String cacheDir;			// cache root directory
	private static Cache cache;				// proxy cache
	public static int nextFd;				// a strictly increasing number to supply the unique file descriptor
	public static RMIInterface stub;		// RMI interface for RPC call to the server
	public static final long chunkSize = 400000;	// maximum size for data transfer in an RPC call

	/* A nested class to handle each individual client */
	private static class FileHandler implements FileHandling {

		// associate a file descriptor to an LRU node in the cache
		private ConcurrentHashMap<Integer, Node> fdToNode = new ConcurrentHashMap<>();
		// track RandomAccessFile object of read file descriptors
		private ConcurrentHashMap<Integer, RandomAccessFile> fdToRead = new ConcurrentHashMap<>();
		// track RandomAccessFile object of write file descriptors
		private ConcurrentHashMap<Integer, RandomAccessFile> fdToWrite = new ConcurrentHashMap<>();
		// hashset to help check whether a file desciptor is read-only
		private HashSet<Integer> readOnlyFds = new HashSet<>();

		/**
		 * RPC call to read the server copy of a file specified by path. Actual content is only fetched
		 * when the server copy is newer than the local proxy one.
		 * @param  path 	file path
		 * @param  o		open option
		 * @param  version	version on proxy
		 * @param  offset	the offset to start reading the server file
		 * @return a ServerFile object with file content and its metadata
		 */
		public synchronized ServerFile readServerFile( String path, OpenOption o, int version, int offset) {
			ServerFile response = new ServerFile(-1);
			try {
				response = stub.readServerFile(path, o, version, offset);
			} catch (RemoteException e) {
				e.printStackTrace();
			}
			return response;
		}

		/**
		 * RPC call to delete the server copy of a file
		 * @param path 	file path
		 * @return 0 if the unlink operation is successful, -1 otherwise
		 */
		public synchronized int unlinkServerFile( String path ) {
			int result = -1;
			try {
				result = stub.unlinkServerFile(path);
			} catch (RemoteException e) {
				e.printStackTrace();
			}
			return result;
		}

		/**
		 * RPC call to write to the server copy of a file starting at a specified offset
		 * @param path		relative file path
		 * @param content   actual content to write
		 * @param offset	starting offset
		 * @return a positive number representing the new file version if write the successful, otherwise a negative errno
		 */
		public synchronized int writeServerFile( String path, byte[] content, int offset) {
			int version = -1;
			try {
				version = stub.writeServerFile(path, content, offset);
			} catch (RemoteException e) {
				e.printStackTrace();
			}
			return version;
		}

		/**
		 * Open a file on server specified by the file path and open option. Only fetch from server if the file doesn't exist
		 * on the proxy or the proxy copy is outdated, otherwise directly read from proxy copy.
		 * @param path	relative file path
		 * @param o	    open mode
		 * @return a positive number for the file descriptor if the operation is successful, otherwise a negative errno
		 */
		public synchronized int open( String path, OpenOption o ) {
			// use intrinsic lock on the cache to avoid concurrent open of the same file
			synchronized (cache) {
				String normalizedPath = Paths.get(path).normalize().toString();
				// cannot access file outside of the cache directory
				if (normalizedPath.startsWith("..")) 
					return Errors.EPERM;
				
				// create subdirectory if needed
				File cacheFile = new File(normalizedPath);
				String parentDirName = cacheFile.getParent();
				if (parentDirName != null) {
					File parentDir = new File(cacheDir + "/" + parentDirName);
					if (!parentDir.exists())
						parentDir.mkdirs();
				}

				// compare proxy copy and server copy
				Node node = cache.getReadableNode(normalizedPath);
				ServerFile response = readServerFile(normalizedPath, o, (node != null) ? node.version : -1, 0);
				long fileSize = response.fileSize;
				if (!response.valid) 
					return response.errno; // operation fail
				
				RandomAccessFile file;
				if (o == OpenOption.CREATE_NEW) {
					// create new file on proxy
					String newFileName = normalizedPath + "_v" + response.version;
					try {
						file = new RandomAccessFile(cacheDir + "/" + newFileName, "rw");
					} catch (Exception e) {
						return Errors.EPERM;
					}
					fdToRead.put(nextFd, file);
					// clean-up stale copy and add cache entry
					Node newNode = new Node(normalizedPath, newFileName, response.version, 0);
					fdToNode.put(nextFd, newNode);
					cache.removeStaleCopy(normalizedPath); 
					if (!cache.addNode(newNode))
						return Errors.EBUSY; // add file fails as it exceeds capacity
					return nextFd++;
				} else if (o == OpenOption.CREATE || o == OpenOption.READ || o == OpenOption.WRITE) {
					if (node != null && response.version == node.version) {
						// file already cached and version is up-to-date
						try {
							file = new RandomAccessFile(cacheDir + "/" + node.fileName, "rw");
						} catch (Exception e) {
							return Errors.EPERM;
						}
						fdToRead.put(nextFd, file);
						fdToNode.put(nextFd, node);
						// update cache entry
						node.incrRef();
						cache.moveFront(node);
					} else {
						// file not on cache or outdated: fetch server copy
						String newFileName =  normalizedPath + "_v" + response.version;
						try {
							file = new RandomAccessFile(cacheDir + '/' + newFileName, "rw");
							file.write(response.content);
							int offset = response.content.length;
							// chunked write
							while (offset < fileSize) {
								response = readServerFile(normalizedPath, o, (node != null) ? node.version : -1, offset);
								file.write(response.content);
								offset += response.content.length;
							}
							file.seek(0); // reset file pointer for next read/write
						} catch (Exception e) {
							return Errors.EPERM;
						}
						fdToRead.put(nextFd, file);
						Node newNode = new Node(normalizedPath, newFileName, response.version, fileSize);
						fdToNode.put(nextFd, newNode);
						cache.removeStaleCopy(normalizedPath);
						if (!cache.addNode(newNode))
							return Errors.EBUSY;
					}
					if (o == OpenOption.READ) 
						readOnlyFds.add(nextFd);
					return nextFd++;
				} else {
					return Errors.EINVAL;
				}
			}
		}

		/**
		 * Close a open file descriptor. If the proxy copy is written to, propagate changes back to server and update the server copy. 
		 * The proxy copy will also be made visible by all clients. All stale copies of the file will be removed.
		 * @param fd	 file desciptor
		 * @return 0 on sucess, a negative number indicating the error occurred otherwise
		 */
		public synchronized int close( int fd ) {
			if (!fdToNode.containsKey(fd)) 
				return Errors.EBADF;
			Node node = fdToNode.get(fd);
			RandomAccessFile file = fdToRead.containsKey(fd) ? fdToRead.get(fd) : fdToWrite.get(fd);
			if (fdToWrite.containsKey(fd)) {
				// propagate changes back to server
				int version = -1;
				long fileSize;
				try {
					file.seek(0);
					fileSize = file.length();
					int offset = 0;
					while (offset < fileSize) {
						// write chunk by chunk
						byte[] writeBuf = new byte[(int) Math.min(chunkSize, fileSize - offset)];
						file.readFully(writeBuf);
						version = writeServerFile(node.pathName, writeBuf, offset);
						offset += writeBuf.length;
					}
				} catch (Exception e) {
					e.printStackTrace();
					return -1;
				}
				// update file version		
				node.updateVersion(version);
				// rename file to reflect the version and change visibility
				String newFileName = node.pathName + "_v" + version;
				File origFile = new File(cacheDir + "/" + node.fileName);
				File newFile = new File(cacheDir + "/" + newFileName);
				origFile.renameTo(newFile);
				node.updateFileName(newFileName);
				// clean-up stale copy
				cache.removeStaleCopy(node.pathName);
			}
			// update cache entry
			cache.moveFront(node);
			node.decrRef();
			fdToNode.remove(fd);
			// close file
			try {
				file.close();
			} catch (Exception e) {
				return Errors.EBADF;
			}
			// clean file entry
			if (fdToRead.containsKey(fd)) {
				readOnlyFds.remove(fd);
				fdToRead.remove(fd);
			} else
				fdToWrite.remove(fd);
			return 0;
		}

		/**
		 * Write to a file indicated by the file desciptor. A new copy of the file will be created and added to the cache 
		 * on the first write to the file descriptor, which is only visible to the client that performs the write.
		 * @param fd   file descriptor
		 * @param buf  actual contents
		 * @return the number of bytes written on success, or a negative errno in case of failure
		 */
		public synchronized long write( int fd, byte[] buf ) {
			// fd is invalid or not open for write
			if (!fdToNode.containsKey(fd) || readOnlyFds.contains(fd)) return Errors.EBADF;
			long fileSize;
			RandomAccessFile readFile, writeFile;
			// create a copy to write, if needed
			if (!fdToWrite.containsKey(fd)) {
				Node readNode = fdToNode.get(fd);
				// assign unique file name
				String newFileName = readNode.fileName + "_write_" + fd;
				try {
					readFile = fdToRead.get(fd);
					fileSize = readFile.length();
					writeFile = new RandomAccessFile(cacheDir + "/" + newFileName, "rw");
					long pos = readFile.getFilePointer();
					// chunked copy from readFile to writeFile
					int offset = 0;
					readFile.seek(offset);
					while (offset < fileSize) {
						byte[] content = new byte[(int) Math.min(chunkSize, fileSize - offset)];
						readFile.readFully(content);
						writeFile.write(content);
						offset += content.length;
					}
					// copy file pointer
					writeFile.seek(pos);
					// close read file
					readFile.close();
				} catch (Exception e) {
					return -1;
				}
				fdToRead.remove(fd);
				fdToWrite.put(fd, writeFile);
				// operations on cache node
				readNode.decrRef();
				Node writeNode = new Node(readNode, newFileName, fileSize);
				fdToNode.put(fd, writeNode);
				cache.addNode(writeNode);
			}

			// write to file
			writeFile = fdToWrite.get(fd);
			try {
				writeFile.write(buf);
				fileSize = writeFile.length();
			} catch (IOException e) {
				return Errors.EBADF;
			}
			// update cache size
			if (!cache.updateNodeSize(fdToNode.get(fd), fileSize))
				return Errors.EBUSY;
			return (long) buf.length;
		}

		/**
		 * Read from a file on proxy.
		 * @param fd	file desciptor to read from
		 * @param buf	byte array to store the contents read
		 * @return the number of bytes actually read on success, or a negative value indicating the error
		 */
		public synchronized long read( int fd, byte[] buf ) {
			if (!fdToNode.containsKey(fd)) return Errors.EBADF;
			long readRes, numBytes;
			try {
				readRes = fdToRead.containsKey(fd) ? (long) fdToRead.get(fd).read(buf): (long) fdToWrite.get(fd).read(buf);
			} catch (Exception e) {
				return Errors.ENOMEM;
			}
			numBytes = (readRes != -1) ? readRes : 0;
			cache.moveFront(fdToNode.get(fd));
			return numBytes;
		}

		/**
		 * Reposition read/write file offset
		 * @param fd	file desciptor 
		 * @param pos	relative position 
		 * @param o		lseek option, either from start, from end or from current file pointer
		 * @return 		the new position on success, or a negative value indicating the error
		 */
		public synchronized long lseek( int fd, long pos, LseekOption o ) {
			if (!fdToNode.containsKey(fd)) return Errors.EBADF;
			Node node = fdToNode.get(fd);
			// calculate seek location
			long seekPos;
			if (o == LseekOption.FROM_START) 
				seekPos = pos;
			else if (o == LseekOption.FROM_END)
				seekPos = node.size + pos;
			else if (o == LseekOption.FROM_CURRENT) {
				long currPos;
				try {
					currPos = fdToRead.containsKey(fd) ? fdToRead.get(fd).getFilePointer() : fdToWrite.get(fd).getFilePointer();
				} catch (Exception e) {
					return -1;
				}
				seekPos = currPos + pos;
			}
			else 
				return Errors.EINVAL;
			// seek on file
			if (seekPos < 0) return Errors.EINVAL;
			try {
				if (fdToRead.containsKey(fd))
					fdToRead.get(fd).seek(seekPos);
				else
					fdToWrite.get(fd).seek(seekPos);
			} catch (IOException e) {
				return -1;
			}
			// update cache entry
			cache.moveFront(node);
			return seekPos;
		}

		/**
		 * Delete a file on the server. Only the server copy will be directly deleted, a proxy copy is either being
		 * used or will be invalided on the next open on the file.
		 * @param path file path
		 * @return 0 on success, a negative value indicating the error occured otherwise
		 */
		public synchronized int unlink( String path ) {
			String normalizedPath = Paths.get(path).normalize().toString();
			if (normalizedPath.startsWith("..")) return Errors.EPERM;
			// delete on the serverside, lazy deletion on proxy
			return unlinkServerFile(normalizedPath);
		}

		/**
		 * End session when a client is done. Clean up all states and open files associated with the client.
		 */
		public void clientdone(){
			// close open files
			for (int fd: fdToNode.keySet())
				this.close(fd);
			// reset state
			fdToRead.clear();
			fdToWrite.clear();
			fdToNode.clear();
			readOnlyFds.clear();
			return;
		}
	}
	
	private static class FileHandlingFactory implements FileHandlingMaking {
		public FileHandling newclient() {
			return new FileHandler();
		}
	}

	/**
	 * Main entry point of the proxy program, which creates an empty cache and establishes a connection to the remote server.
	 */
	public static void main(String[] args) throws IOException {
		serverIP = args[0];
		serverPort = Integer.valueOf(args[1]);
		// initialize cache
		cacheDir = args[2];
		cache = new Cache(Integer.valueOf(args[3]), cacheDir);
		nextFd = 0;
		// connect to remote server
		try {
			Registry registry = LocateRegistry.getRegistry(serverIP, serverPort);
			stub = (RMIInterface) registry.lookup("RMIInterface");
		} catch (RemoteException e) {
			e.printStackTrace();
		} catch (NotBoundException e) {
			e.printStackTrace();
		}
		(new RPCreceiver(new FileHandlingFactory())).run();
	}
}

