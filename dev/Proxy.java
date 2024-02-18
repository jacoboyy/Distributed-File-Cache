/* Sample skeleton for proxy */

import java.io.*;
import java.util.*;
import java.nio.file.*;
import java.rmi.NotBoundException;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;

class Proxy {
	
	private static String serverIP;
	private static int serverPort;
	private static String cacheDir;

	private static class FileHandler implements FileHandling {

		private int nextFd = 0;
		private HashMap<Integer, RandomAccessFile> fdToFile = new HashMap<Integer, RandomAccessFile>();
		private HashMap<Integer, File> fdToDir = new HashMap<Integer, File>();
		private HashMap<Integer, String> fdToPath = new HashMap<Integer, String>();
		private HashSet<Integer> fdWritten = new HashSet<Integer>();

		public synchronized ServerFile readServerFile( String path, OpenOption o ) {
			RMIInterface stub;
			try {
				Registry registry = LocateRegistry.getRegistry(serverIP, serverPort);
				stub = (RMIInterface) registry.lookup("RMIInterface");
				ServerFile response = stub.readServerFile(path, o);
				return response;
			} catch (RemoteException e) {
				System.err.println("Unable to locate registry or unable to call RPC readServerFile");
				e.printStackTrace();
				System.exit(1);
			} catch (NotBoundException e) {
				System.err.println("RMIInterface not found");
				e.printStackTrace();
				System.exit(1);
			}
			return new ServerFile(-1);
		}

		public synchronized int unlinkServerFile( String path ) {
			RMIInterface stub;
			try {
				Registry registry = LocateRegistry.getRegistry(serverIP, serverPort);
				stub = (RMIInterface) registry.lookup("RMIInterface");
				return stub.unlinkServerFile(path);
			} catch (RemoteException e) {
				System.err.println("Unable to locate registry or unable to call RPC readServerFile");
				e.printStackTrace();
				System.exit(1);
			} catch (NotBoundException e) {
				System.err.println("RMIInterface not found");
				e.printStackTrace();
				System.exit(1);
			}
			return -1;
		}

		public synchronized void writeServerFile( String path, ServerFile file) {
			RMIInterface stub;
			try {
				Registry registry = LocateRegistry.getRegistry(serverIP, serverPort);
				stub = (RMIInterface) registry.lookup("RMIInterface");
				stub.writeServerFile(path, file);
			} catch (RemoteException e) {
				System.err.println("Unable to locate registry or unable to call RPC readServerFile");
				e.printStackTrace();
				System.exit(1);
			} catch (NotBoundException e) {
				System.err.println("RMIInterface not found");
				e.printStackTrace();
				System.exit(1);
			}
		}

		public synchronized int open( String path, OpenOption o ) {
			String normalizedPath = Paths.get(path).normalize().toString();
			if (normalizedPath.startsWith("..")) return Errors.EPERM;
			String pathOnProxy = cacheDir + "/" + normalizedPath;
			File file = new File(pathOnProxy);
			if (o == OpenOption.CREATE) {
				// file already cached
				if (file.exists()) {
					if (file.isDirectory())  return Errors.EISDIR;
					try {
						RandomAccessFile randomAccessFile = new RandomAccessFile(file, "rw");
						fdToFile.put(nextFd, randomAccessFile);
						fdToPath.put(nextFd, normalizedPath);
					} catch (Exception e) {
						return Errors.EPERM;
					}
				} else {
					// fetch from server
					ServerFile response = readServerFile(normalizedPath, o);
					if (!response.valid) return response.errno;
					try {
						RandomAccessFile writeFile = new RandomAccessFile(file, "rw");
						writeFile.write(response.content); // write file content
						writeFile.seek(0); // reset file pointer
						fdToFile.put(nextFd, writeFile);
						fdToPath.put(nextFd, normalizedPath);
					} catch (Exception e) {
						return Errors.EPERM;
					}
				} 
				return nextFd++;
			} else if (o == OpenOption.CREATE_NEW) {
				if (file.exists()) return Errors.EEXIST;
				// check if can create new file on the serverside
				ServerFile response = readServerFile(normalizedPath, o);
				if (!response.valid) return response.errno;
				try {
					RandomAccessFile randomAccessFile = new RandomAccessFile(file, "rw");
					fdToFile.put(nextFd, randomAccessFile);
					fdToPath.put(nextFd, normalizedPath);
				} catch (Exception e) {
					return Errors.EPERM;
				}
				return nextFd++;
			} else if (o == OpenOption.READ) {
				if (!file.exists()) {
					// fetch from server
					ServerFile response = readServerFile(normalizedPath, o);
					if (!response.valid) return response.errno;
					// create file on proxy
					try {
						RandomAccessFile writeFile = new RandomAccessFile(file, "rw");
						writeFile.write(response.content);
						writeFile.close();
						RandomAccessFile readFile = new RandomAccessFile(file, "r");
						fdToFile.put(nextFd, readFile);
						fdToPath.put(nextFd, normalizedPath);
					} catch (Exception e) {
						return Errors.EPERM;
					}
				} else {
					// TODO: deal with directory
					try {
						RandomAccessFile randomAccessFile = new RandomAccessFile(file, "r");
						fdToFile.put(nextFd, randomAccessFile);
						fdToPath.put(nextFd, normalizedPath);
					} catch (Exception e1) {
						return Errors.EPERM;
					}
				}
				return nextFd++;
			} else if (o == OpenOption.WRITE) {
				if (!file.exists()) {
					// fetch from server
					ServerFile response = readServerFile(normalizedPath, o);
					if (!response.valid) return response.errno;
					try {
						RandomAccessFile writeFile = new RandomAccessFile(file, "rw");
						writeFile.write(response.content);
						writeFile.seek(0); // reset file pointer
						fdToFile.put(nextFd, writeFile);
						fdToPath.put(nextFd, normalizedPath);
					} catch (Exception e) {
						return Errors.EPERM;
					}
				} else {
					if (file.isDirectory()) return Errors.EISDIR;
					try {
						RandomAccessFile randomAccessFile = new RandomAccessFile(file, "rw");
						fdToFile.put(nextFd, randomAccessFile);
						fdToPath.put(nextFd, normalizedPath);
					} catch (Exception e1) {
						return Errors.EPERM;
					}
				}
				return nextFd++;
			}
			return Errors.EINVAL;
		}

		public synchronized int close( int fd ) {
			if (!fdToFile.containsKey(fd) && !fdToDir.containsKey(fd)) return Errors.EBADF;
			try {
				if (fdToFile.containsKey(fd)){
					// if file is modified: propogate the file back
					if (fdWritten.contains(fd)) {
						RandomAccessFile randomAccessFile = fdToFile.get(fd);
						randomAccessFile.seek(0);
						byte[] content = new byte[(int) randomAccessFile.length()];
						randomAccessFile.readFully(content);
						writeServerFile(fdToPath.get(fd), new ServerFile(content));
						fdWritten.remove(fd);
					}
					// closing a open randomAccessFile
					fdToFile.get(fd).close();
					fdToFile.remove(fd);
					fdToPath.remove(fd);
				} else {
					// close a open directory
					fdToDir.remove(fd);	
				}
				return 0;
			} catch (IOException e) {
				return Errors.EBADF;
			}
		}

		public synchronized long write( int fd, byte[] buf ) {
			if (!fdToFile.containsKey(fd)) return Errors.EBADF;
			try {
				fdToFile.get(fd).write(buf);
				fdWritten.add(fd); // indicate file modification
				return (long) buf.length;
			} catch (IOException e) {
				// e.g. write to a read-only file
				return Errors.EBADF;
			}
		}

		public synchronized long read( int fd, byte[] buf ) {
			if (!fdToFile.containsKey(fd) && !fdToDir.containsKey(fd)) return Errors.EBADF;
			if (fdToDir.containsKey(fd)) return Errors.EISDIR;
			try {
				long readRes = (long) fdToFile.get(fd).read(buf);
				long numBytes = (readRes != -1) ? readRes : 0;
				return numBytes;
			} catch (Exception e) {
				return Errors.ENOMEM;
			}
		}

		public synchronized long lseek( int fd, long pos, LseekOption o ) {
			if (!fdToFile.containsKey(fd)) return Errors.EBADF;
			try {
				// calculate seek location
				long seekPos;
				if (o == LseekOption.FROM_START) seekPos = pos;
				else if (o == LseekOption.FROM_END) seekPos = fdToFile.get(fd).length() + pos;
				else if (o == LseekOption.FROM_CURRENT) seekPos = fdToFile.get(fd).getFilePointer() + pos;
				else return Errors.EINVAL;
				// seek on file
				if (seekPos < 0) return Errors.EINVAL;
				fdToFile.get(fd).seek(seekPos);
				return seekPos;
			} catch (IOException e) {
				return -1;
			}
		}

		public synchronized int unlink( String path ) {
			String normalizedPath = Paths.get(path).normalize().toString();
			if (normalizedPath.startsWith("..")) return Errors.EPERM;
			// delete on the proxy side first
			File proxyFile = new File(cacheDir + '/' + normalizedPath);
			if (proxyFile.isDirectory()) return Errors.EISDIR;
			if (proxyFile.exists()) proxyFile.delete();
			// delete on the serverside
			return unlinkServerFile(normalizedPath);
		}

		public synchronized void clientdone(){
			// clean up internal data structure and reset state
			fdToFile.forEach((fd, file) -> {
				try {
					file.close();
				} catch (Exception e){
					e.printStackTrace();
				}
			});
			fdToDir.clear();
			fdToFile.clear();
			fdToPath.clear();
			nextFd = 0;
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
		(new RPCreceiver(new FileHandlingFactory())).run();
	}
}

