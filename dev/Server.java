/*
 * @file:   Server.java
 * @author: Jacob Wang <tengdaw@andrew.cmu.edu>
 * Implementation of a remote server that utilizes java RMI to serve read/write/unlink RPCs from a caching proxy.
 * The server can handle multiple proxies concurrently.
 */

import java.io.*;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;
import java.util.concurrent.ConcurrentHashMap;

public class Server extends UnicastRemoteObject implements RMIInterface {

    private String rootdir;                                     // server root directory
    private ConcurrentHashMap<String, Integer> pathToVersion;   // track version of files on server
    private static final long chunkSize = 400000;                            // maximum data transfer size in an RPCs

    /**
     * Constructor class for the server object
     * @param port    server port number
     * @param roodir  server root directory
     */
    public Server(int port, String rootdir) throws RemoteException {
        super(port);
        this.rootdir = rootdir;
        pathToVersion = new ConcurrentHashMap<>();
    }

    /**
     * Read a file on server and send its file content back only if the proxy version doesn't exist or is outdated.
     * The size to content read is capped by the chunk size.
     * @param path              file name
     * @param o                 open mode
     * @param versionOnProxy    version of file on proxy, -1 if file doesn't exist
     * @param offset            offset to start reading the file
     * @return a ServerFile object, with the file content if the read is sucessful or the negative errno if read fails.
     */
    public synchronized ServerFile readServerFile(String path, FileHandling.OpenOption o, int versionOnProxy, int offset) {
        // open file, read content, and send back to proxy
        File file = new File(rootdir + '/' + path);

        // non-initial read after chunking
        if (offset != 0) {
            // file already exists on server, read up to chunksize
            try {
                RandomAccessFile readFile = new RandomAccessFile(file, "r");
                long fileSize = readFile.length();
                readFile.seek(offset);
                byte[] content = new byte[(int) Math.min(chunkSize, fileSize - offset)];
                readFile.readFully(content);
                readFile.close();
                return new ServerFile(content, pathToVersion.get(path), fileSize);
            } catch (Exception e) {
                e.printStackTrace();
                return new ServerFile(-1);
            }
        }

        if (o == FileHandling.OpenOption.CREATE) {
            if (file.isDirectory()) 
				return new ServerFile(FileHandling.Errors.EISDIR);
            try {
                if (file.exists()) {
                    if (!pathToVersion.containsKey(path))
                        pathToVersion.put(path, 1);
                    int versionOnServer = pathToVersion.get(path);
                    if (versionOnServer == versionOnProxy)
                        return new ServerFile(new byte[0], versionOnServer, 0);
                    // server has newer version: copy contents and return it to client
                    RandomAccessFile readFile = new RandomAccessFile(file, "rw");
                    long fileSize = readFile.length();
                    byte[] content = new byte[(int) Math.min(chunkSize, fileSize)];
                    readFile.readFully(content);
                    readFile.close();
                    return new ServerFile(content, versionOnServer, fileSize);
                } else {
                    // file doesn't exist
                    return new ServerFile(new byte[0], pathToVersion.containsKey(path) ? pathToVersion.get(path): 0, 0);
                }
            } catch (Exception e) {
                return new ServerFile(FileHandling.Errors.EPERM);
            }
        } else if (o == FileHandling.OpenOption.CREATE_NEW) {
            if (file.isDirectory()) 
				return new ServerFile(FileHandling.Errors.EISDIR);
            if (file.exists()) 
				return new ServerFile(FileHandling.Errors.EEXIST);
            return new ServerFile(new byte[0], pathToVersion.containsKey(path) ? pathToVersion.get(path): 0, 0);
        } else if (o == FileHandling.OpenOption.READ) {
            if (!file.exists()) 
				return new ServerFile(FileHandling.Errors.ENOENT);
            if (!pathToVersion.containsKey(path))
                pathToVersion.put(path, 1);
            int versionOnServer = pathToVersion.get(path);
            if (versionOnServer == versionOnProxy)
                return new ServerFile(new byte[0], versionOnServer, 0);
            try {
                // server has newer version: copy contents and return it to client
                RandomAccessFile readFile = new RandomAccessFile(file, "r");
                long fileSize = readFile.length();
                byte[] content = new byte[(int) Math.min(chunkSize, fileSize)];
                readFile.readFully(content);
                readFile.close();
                return new ServerFile(content, versionOnServer, fileSize);
            } catch (Exception e) {
                return new ServerFile(FileHandling.Errors.EPERM);
            }
        } else {
            if (!file.exists()) 
                return new ServerFile(FileHandling.Errors.ENOENT);
            if (file.isDirectory()) 
                return new ServerFile(FileHandling.Errors.EISDIR);
            if (!pathToVersion.containsKey(path))
                pathToVersion.put(path, 1);
            int versionOnServer = pathToVersion.get(path);
            if (versionOnServer == versionOnProxy)
                return new ServerFile(new byte[0], versionOnServer, 0);
            try {
                // server has newer version: copy contents and return it to client
                RandomAccessFile readFile = new RandomAccessFile(file, "rw");
                long fileSize = readFile.length();
                byte[] content = new byte[(int) Math.min(chunkSize, fileSize)];
                readFile.readFully(content);
                return new ServerFile(content, versionOnServer, fileSize);
            } catch (Exception e) {
                return new ServerFile(FileHandling.Errors.EPERM);
            }
        }
    }

    /**
     * Write all contents in an input byte array to a file starting at a given offset.
     * The file version is updated accordingly.
     * @param path      server file path
     * @param content   file contents to write
     * @param offset    starting offset
     * @return the updated file version
     */
    public synchronized int writeServerFile(String path, byte[] content, int offset) {
        int newVersion;
        if (offset == 0) {
            newVersion = pathToVersion.containsKey(path) ? pathToVersion.get(path) + 1 : 1;
            pathToVersion.put(path, newVersion);
        } else 
            newVersion = pathToVersion.get(path);
        
        try {
            // actual write
            RandomAccessFile file = new RandomAccessFile(rootdir + '/' + path, "rw");
            file.seek(offset);
            file.write(content);
            file.close();
        } catch (IOException e) {
            return -1;
        }
        return newVersion;
    }

    /**
     * Delete a file on server and update file version.
     * @param path  server file path
     * @return 0 if operation is successful, or a nagative number indicating the error number otherwise
     */
    public synchronized int unlinkServerFile(String path) {
        File serverFile = new File(rootdir + '/' + path);
        if (!serverFile.exists()) return FileHandling.Errors.ENOENT;
        if (serverFile.isDirectory()) return FileHandling.Errors.EISDIR;
        // pump up file version
        if (pathToVersion.containsKey(path))
            pathToVersion.put(path, pathToVersion.get(path) + 1);
        int res = serverFile.delete() ? 0 : -1;
        return res;
    }

    /**
     * Main entry point of the server program. It reads port and root directory from the
     * command line and starts to listen to connection requests from proxy.
     */
    public static void main(String args[]) {
        int port = Integer.valueOf(args[0]);
        String rootdir = args[1];
        try {
            Server rmiServer = new Server(port, rootdir);
            // Create a registry that would be listening at the same port
            Registry registry = LocateRegistry.createRegistry(port);
            // Bind the exported remote object in the registry
            registry.bind("RMIInterface", rmiServer);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
