/* Sample skeleton for proxy */

import java.io.*;
import java.util.*;
import java.nio.file.*;
import java.rmi.NotBoundException;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.util.concurrent.ConcurrentHashMap;

class Proxy {
	
	private static String serverIP;
	private static int serverPort;
	private static String cacheDir;
	private static Cache cache;
	public static int nextFd;
	public static RMIInterface stub;
	public static long chunkSize = 400000;

	private static class FileHandler implements FileHandling {

		private ConcurrentHashMap<Integer, Node> fdToNode = new ConcurrentHashMap<>();
		private ConcurrentHashMap<Integer, RandomAccessFile> fdToRead = new ConcurrentHashMap<>();
		private ConcurrentHashMap<Integer, RandomAccessFile> fdToWrite = new ConcurrentHashMap<>();
		private ConcurrentHashMap<Integer, File> fdToDir = new ConcurrentHashMap<>();
		private HashSet<Integer> readOnlyFds = new HashSet<>();

		public synchronized ServerFile readServerFile( String path, OpenOption o, int version, int offset) {
			ServerFile response = new ServerFile(-1);
			try {
				response = stub.readServerFile(path, o, version, offset);
			} catch (RemoteException e) {
				System.err.println("Unable to call RPC readServerFile");
				e.printStackTrace();
				System.exit(1);
			}
			return response;
		}

		public synchronized int unlinkServerFile( String path ) {
			int result = -1;
			try {
				result = stub.unlinkServerFile(path);
			} catch (RemoteException e) {
				System.err.println("Unable to call RPC unlinkServerFile");
				e.printStackTrace();
				System.exit(1);
			}
			return result;
		}

		public synchronized int writeServerFile( String path, byte[] content, int offset) {
			int version = -1;
			try {
				version = stub.writeServerFile(path, content, offset);
			} catch (RemoteException e) {
				System.err.println("Unable to call RPC writeServerFile");
				e.printStackTrace();
				System.exit(1);
			}
			return version;
		}

		public synchronized int open( String path, OpenOption o ) {
			synchronized (cache) {
				System.err.println("Open called on " + path + " with OpenOption = " + o);
				String normalizedPath = Paths.get(path).normalize().toString();
				if (normalizedPath.startsWith("..")) return Errors.EPERM;
				
				// create subdirectory if needed
				File cacheFile = new File(normalizedPath);
				String parentDirName = cacheFile.getParent();
				if (parentDirName != null) {
					File parentDir = new File(cacheDir + "/" + parentDirName);
					if (!parentDir.exists()) {
						System.err.println("Create parent dir " + parentDirName + " of " + normalizedPath);
						parentDir.mkdirs();
					}
				}

				// check if proxy has copy
				Node node = cache.getReadableNode(normalizedPath);
				// check file status on server
				ServerFile response = readServerFile(normalizedPath, o, (node != null) ? node.version : -1, 0);
				if (!response.valid) return response.errno;
				long fileSize = response.fileSize;
				
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
					Node newNode = new Node(normalizedPath, newFileName, 1, response.version, 0);
					fdToNode.put(nextFd, newNode);
					// clean-up stale cache copies
					cache.removeStaleCopy(normalizedPath);
					if (!cache.addNode(newNode))
						return Errors.EBUSY;
					return nextFd++;
				} else if (o == OpenOption.CREATE || o == OpenOption.READ || o == OpenOption.WRITE) {
					if (node != null && response.version == node.version) {
						// file already cached and version is up-to-date
						System.err.println("file cached and version up-to-date");
						try {
							file = new RandomAccessFile(cacheDir + "/" + node.fileName, "rw");
						} catch (Exception e) {
							return Errors.EPERM;
						}
						fdToRead.put(nextFd, file);
						fdToNode.put(nextFd, node);
						node.incrRef();
						cache.moveFront(node);
					} else {
						// file not on cache or outdated
						String newFileName =  normalizedPath + "_v" + response.version;
						try {
							file = new RandomAccessFile(cacheDir + '/' + newFileName, "rw");
							file.write(response.content);
							int offset = response.content.length;
							// perform chunked write
							while (offset < fileSize) {
								response = readServerFile(normalizedPath, o, (node != null) ? node.version : -1, offset);
								file.write(response.content);
								offset += response.content.length;
							}
							// reset file pointer for next read/write
							file.seek(0);
						} catch (Exception e) {
							return Errors.EPERM;
						}
						fdToRead.put(nextFd, file);
						Node newNode = new Node(normalizedPath, newFileName, 1, response.version, fileSize);
						fdToNode.put(nextFd, newNode);
						// clean-up stale cache copies
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

		public synchronized int close( int fd ) {
			System.err.println("close called on fd " + fd);
			// fd is associated with an open directory
			if (fdToDir.containsKey(fd)) {
				fdToDir.remove(fd);
				return 0;
			}

			if (!fdToNode.containsKey(fd)) 
				return Errors.EBADF;
			Node node = fdToNode.get(fd);
			RandomAccessFile file = fdToRead.containsKey(fd) ? fdToRead.get(fd) : fdToWrite.get(fd);
			if (fdToWrite.containsKey(fd)) {
				System.err.println("Propagate changes in " + node.fileName + " back to server!");
				// propagate changes back to server
				int version = -1;
				long fileSize;
				try {
					file.seek(0);
					fileSize = file.length();
					int offset = 0;
					while (offset < fileSize) {
						// write at most chunksize
						byte[] writeBuf = new byte[(int) Math.min(chunkSize, fileSize - offset)];
						// copy file content
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
				// rename file and change visibility
				String newFileName = node.pathName + "_v" + version;
				File origFile = new File(cacheDir + "/" + node.fileName);
				File newFile = new File(cacheDir + "/" + newFileName);
				origFile.renameTo(newFile);
				System.err.println("Write file renamed from " + node.fileName + " to " + newFileName);
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

		public synchronized long write( int fd, byte[] buf ) {
			System.err.println("Write " + buf.length + " bytes to fd " + fd);
			// fd is invalid or not open for write
			if (!fdToNode.containsKey(fd) || readOnlyFds.contains(fd)) return Errors.EBADF;
			long fileSize;
			RandomAccessFile readFile, writeFile;
			// create new write file on first write
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
				// e.g. write to a read-only file
				return Errors.EBADF;
			}
			// update cache size
			if (!cache.updateNodeSize(fdToNode.get(fd), fileSize))
				return Errors.EBUSY;
			return (long) buf.length;
		}

		public synchronized long read( int fd, byte[] buf ) {
			
			if (!fdToNode.containsKey(fd)) return Errors.EBADF;
			if (fdToDir.containsKey(fd)) return Errors.EISDIR;
			long readRes, numBytes;
			try {
				readRes = fdToRead.containsKey(fd) ? (long) fdToRead.get(fd).read(buf): (long) fdToWrite.get(fd).read(buf);
			} catch (Exception e) {
				return Errors.ENOMEM;
			}
			numBytes = (readRes != -1) ? readRes : 0;
			cache.moveFront(fdToNode.get(fd));
			System.err.println("Read " + numBytes + " bytes from fd " + fd);
			return numBytes;
		}

		public synchronized long lseek( int fd, long pos, LseekOption o ) {
			System.err.println("Lseek on fd" + fd + " with pos = " + pos + " and LseekOption = " + o);
			if (!fdToNode.containsKey(fd)) return Errors.EBADF;
			// calculate seek location
			Node node = fdToNode.get(fd);
			long seekPos;
			if (o == LseekOption.FROM_START) 
				seekPos = pos;
			else if (o == LseekOption.FROM_END)
				seekPos = node.getFileSize() + pos;
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

		public synchronized int unlink( String path ) {
			String normalizedPath = Paths.get(path).normalize().toString();
			if (normalizedPath.startsWith("..")) return Errors.EPERM;
			// delete on the serverside, lazy deletion on proxy
			return unlinkServerFile(normalizedPath);
		}

		public void clientdone(){
			// close open files
			for (int fd: fdToNode.keySet())
				close(fd);
			// reset state
			fdToRead.clear();
			fdToWrite.clear();
			fdToNode.clear();
			fdToDir.clear();
			readOnlyFds.clear();
			return;
		}
	}
	
	private static class FileHandlingFactory implements FileHandlingMaking {
		public FileHandling newclient() {
			return new FileHandler();
		}
	}

	public static void main(String[] args) throws IOException {
		serverIP = args[0];
		serverPort = Integer.valueOf(args[1]);
		cacheDir = args[2];
		cache = new Cache(Integer.valueOf(args[3]), cacheDir);
		nextFd = 0;
		// look up remote server
		try {
			Registry registry = LocateRegistry.getRegistry(serverIP, serverPort);
			stub = (RMIInterface) registry.lookup("RMIInterface");
		} catch (RemoteException e) {
			System.err.println("Unable to locate registry");
			e.printStackTrace();
			System.exit(1);
		} catch (NotBoundException e) {
			System.err.println("RMIInterface not found");
			e.printStackTrace();
			System.exit(1);
		}
		(new RPCreceiver(new FileHandlingFactory())).run();
	}
}

