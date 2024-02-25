import java.rmi.Remote;
import java.rmi.RemoteException;

interface RMIInterface extends Remote {
    ServerFile readServerFile(String path, FileHandling.OpenOption o, int version) throws RemoteException;
    int writeServerFile(String path, byte[] content) throws RemoteException;
    int unlinkServerFile(String path) throws RemoteException;
}
