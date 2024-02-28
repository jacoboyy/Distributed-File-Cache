import java.rmi.Remote;
import java.rmi.RemoteException;

interface RMIInterface extends Remote {
    ServerFile readServerFile(String path, FileHandling.OpenOption o, int version, int offset) throws RemoteException;
    int writeServerFile(String path, byte[] content, int offset) throws RemoteException;
    int unlinkServerFile(String path) throws RemoteException;
}
