import java.io.File;
import java.io.RandomAccessFile;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;

public class Server extends UnicastRemoteObject implements RMIInterface {

    private String rootdir;

    public Server(int port, String rootdir) throws RemoteException {
        super(port);
        this.rootdir = rootdir;
    }

    public synchronized ServerFile readServerFile(String path, FileHandling.OpenOption o) {
        // open file, read content, and send back to proxy
        File file = new File(rootdir + '/' + path);
        if (o == FileHandling.OpenOption.CREATE) {
            if (file.isDirectory()) return new ServerFile(FileHandling.Errors.EISDIR);
            try {
                if (file.exists()) {
                    // file exists: read content
                    RandomAccessFile randomAccessFile = new RandomAccessFile(file, "rw");
                    byte[] content = new byte[(int) randomAccessFile.length()];
                    randomAccessFile.readFully(content);
                    return new ServerFile(content);
                } else {
                    // file doesn't exist
                    return new ServerFile(new byte[0]);
                }
            } catch (Exception e) {
                return new ServerFile(FileHandling.Errors.EPERM);
            }
        } else if (o == FileHandling.OpenOption.CREATE_NEW) {
            if (file.isDirectory()) return new ServerFile(FileHandling.Errors.EISDIR);
            if (file.exists()) return new ServerFile(FileHandling.Errors.EEXIST);
            return new ServerFile(new byte[0]);
        } else if (o == FileHandling.OpenOption.READ) {
            if (!file.exists()) return new ServerFile(FileHandling.Errors.ENOENT);
            // TODO: how to deal with direcory?
            try {
                RandomAccessFile randomAccessFile = new RandomAccessFile(file, "r");
                byte[] content = new byte[(int) randomAccessFile.length()];
                randomAccessFile.readFully(content);
                return new ServerFile(content);
            } catch (Exception e) {
                return new ServerFile(FileHandling.Errors.EPERM);
            }
        } else {
            if (!file.exists()) return new ServerFile(FileHandling.Errors.ENOENT);
            if (file.isDirectory()) return new ServerFile(FileHandling.Errors.EISDIR);
            try {
                RandomAccessFile randomAccessFile = new RandomAccessFile(file, "rw");
                byte[] content = new byte[(int) randomAccessFile.length()];
                randomAccessFile.readFully(content);
                return new ServerFile(content);
            } catch (Exception e1) {
                return new ServerFile(FileHandling.Errors.EPERM);
            }
        }
    }

    public synchronized void writeServerFile(String path, ServerFile file) {
        try {
            RandomAccessFile writeFile = new RandomAccessFile(rootdir + '/' + path, "rw");
            writeFile.write(file.content);
            writeFile.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public synchronized int unlinkServerFile(String path) {
        File serverFile = new File(rootdir + '/' + path);
        if (!serverFile.exists()) return FileHandling.Errors.ENOENT;
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
