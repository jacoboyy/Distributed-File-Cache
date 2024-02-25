import java.io.*;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;
import java.util.concurrent.ConcurrentHashMap;

public class Server extends UnicastRemoteObject implements RMIInterface {

    private String rootdir;
    private ConcurrentHashMap<String, Integer> pathToVersion;

    public Server(int port, String rootdir) throws RemoteException {
        super(port);
        this.rootdir = rootdir;
        pathToVersion = new ConcurrentHashMap<>();
    }

    public synchronized ServerFile readServerFile(String path, FileHandling.OpenOption o, int versionOnProxy) {
        // open file, read content, and send back to proxy
        File file = new File(rootdir + '/' + path);
        if (o == FileHandling.OpenOption.CREATE) {
            if (file.isDirectory()) return new ServerFile(FileHandling.Errors.EISDIR);
            try {
                if (file.exists()) {
                    if (!pathToVersion.containsKey(path))
                        pathToVersion.put(path, 1);
                    int versionOnServer = pathToVersion.get(path);
                    if (versionOnServer == versionOnProxy)
                        return new ServerFile(new byte[0], versionOnServer);
                    // server version is newer, read actual contents
                    RandomAccessFile randomAccessFile = new RandomAccessFile(file, "rw");
                    byte[] content = new byte[(int) randomAccessFile.length()];
                    randomAccessFile.readFully(content);
                    return new ServerFile(content, versionOnServer);
                } else {
                    // file doesn't exist
                    return new ServerFile(new byte[0], pathToVersion.containsKey(path) ? pathToVersion.get(path): 0);
                }
            } catch (Exception e) {
                return new ServerFile(FileHandling.Errors.EPERM);
            }
        } else if (o == FileHandling.OpenOption.CREATE_NEW) {
            if (file.isDirectory()) return new ServerFile(FileHandling.Errors.EISDIR);
            if (file.exists()) return new ServerFile(FileHandling.Errors.EEXIST);
            return new ServerFile(new byte[0], pathToVersion.containsKey(path) ? pathToVersion.get(path): 0);
        } else if (o == FileHandling.OpenOption.READ) {
            if (!file.exists()) return new ServerFile(FileHandling.Errors.ENOENT);
            // TODO: how to deal with direcory?
            if (!pathToVersion.containsKey(path))
                pathToVersion.put(path, 1);
            int versionOnServer = pathToVersion.get(path);
            if (versionOnServer == versionOnProxy)
                return new ServerFile(new byte[0], versionOnServer);
            try {
                // server has newer version
                RandomAccessFile randomAccessFile = new RandomAccessFile(file, "r");
                byte[] content = new byte[(int) randomAccessFile.length()];
                randomAccessFile.readFully(content);
                return new ServerFile(content, versionOnServer);
            } catch (Exception e) {
                return new ServerFile(FileHandling.Errors.EPERM);
            }
        } else {
            if (!file.exists()) return new ServerFile(FileHandling.Errors.ENOENT);
            if (file.isDirectory()) return new ServerFile(FileHandling.Errors.EISDIR);
            if (!pathToVersion.containsKey(path))
                pathToVersion.put(path, 1);
            int versionOnServer = pathToVersion.get(path);
            if (versionOnServer == versionOnProxy)
                return new ServerFile(new byte[0], versionOnServer);
            try {
                // server has newer version
                RandomAccessFile randomAccessFile = new RandomAccessFile(file, "rw");
                byte[] content = new byte[(int) randomAccessFile.length()];
                randomAccessFile.readFully(content);
                return new ServerFile(content, versionOnServer);
            } catch (Exception e) {
                return new ServerFile(FileHandling.Errors.EPERM);
            }
        }
    }

    public synchronized int writeServerFile(String path, byte[] content) {
        int newVersion = pathToVersion.containsKey(path) ? pathToVersion.get(path) + 1 : 1;
        pathToVersion.put(path, newVersion);
        try {
            // update file content
            RandomAccessFile file = new RandomAccessFile(rootdir + '/' + path, "rw");
            file.write(content);
            file.close();
        } catch (IOException e) {
            e.printStackTrace();
            return -1;
        }
        return newVersion;
    }

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

    public static void main(String args[]) {
        System.err.println("Starting Server..");
        int port = Integer.valueOf(args[0]);
        String rootdir = args[1];
        try {
            Server rmiServer = new Server(port, rootdir);
            // Create a registry that would be listening at the same port
            Registry registry = LocateRegistry.createRegistry(port);
            // Bind the exported remote object in the registry
            registry.bind("RMIInterface", rmiServer);
            System.err.println("Server ready");
        } catch (Exception e) {
            System.err.println("Server exception: " + e.toString());
            e.printStackTrace();
        }
    }
}
