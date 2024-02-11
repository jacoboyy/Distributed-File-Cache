/* Sample skeleton for proxy */

import java.io.*;
import java.util.*;
import java.nio.file.*;

class Proxy {
	
	private static class FileHandler implements FileHandling {

		private int nextFd = 0;
		private HashMap<Integer, RandomAccessFile> fdToFile = new HashMap<Integer, RandomAccessFile>();
		private HashMap<Integer, File> fdToDir = new HashMap<Integer, File>();

		public synchronized int open( String path, OpenOption o ) {
			// simplify file path
			String normalizedPath = Paths.get(path).normalize().toString();
			// restrict access to parent directory
			if (normalizedPath.startsWith("..")) return Errors.EPERM;
			File file = new File(normalizedPath.isEmpty() ? "./" : normalizedPath);

			if (o == OpenOption.CREATE) {
				if (file.isDirectory())  return Errors.EISDIR;
				try {
					RandomAccessFile randomAccessFile = new RandomAccessFile(file, "rw");
					fdToFile.put(nextFd, randomAccessFile);
				} catch (Exception e1) {
					return Errors.EPERM;
				}
				return nextFd++;
			} else if (o == OpenOption.CREATE_NEW) {
				if (file.isDirectory()) return Errors.EISDIR;
				if (file.exists()) return Errors.EEXIST;
				try {
					RandomAccessFile randomAccessFile = new RandomAccessFile(file, "rw");
					fdToFile.put(nextFd, randomAccessFile);
				} catch (Exception e1) {
					return Errors.EPERM;
				} 
				return nextFd++;
			} else if (o == OpenOption.READ) {
				if (!file.exists()) return Errors.ENOENT;
				if (file.isDirectory()){
					File dir = new File(normalizedPath.isEmpty() ? "./" : normalizedPath);
					fdToDir.put(nextFd, dir);
				} else {
					try {
						RandomAccessFile randomAccessFile = new RandomAccessFile(file, "r");
						fdToFile.put(nextFd, randomAccessFile);
					} catch (Exception e1) {
						return Errors.EPERM;
					}
				}
				return nextFd++;
			} else if (o == OpenOption.WRITE){
				if (!file.exists()) return Errors.ENOENT;
				if (file.isDirectory()) return Errors.EISDIR;
				try {
					RandomAccessFile randomAccessFile = new RandomAccessFile(file, "rw");
					fdToFile.put(nextFd, randomAccessFile);
				} catch (Exception e1) {
					return Errors.EPERM;
				} 
				return nextFd++;
			} else {
				return Errors.EINVAL;
			}
		}

		public synchronized int close( int fd ) {
			if (!fdToFile.containsKey(fd) && !fdToDir.containsKey(fd)) return Errors.EBADF;
			try {
				if (fdToFile.containsKey(fd)){
					// closing a open randomAccessFile
					fdToFile.get(fd).close();
					fdToFile.remove(fd);
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
			File file = new File(normalizedPath.isEmpty() ? "./" : normalizedPath);
			if (file.isDirectory()) return Errors.EISDIR;
			if (!file.exists()) return Errors.ENOENT;
			// need to check if deletion is succecssful
			int res = file.delete() ? 0 : -1;
			return res;
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
		(new RPCreceiver(new FileHandlingFactory())).run();
	}
}

