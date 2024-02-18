import java.rmi.Remote;
import java.rmi.RemoteException;

interface RMIInterface extends Remote {
    ServerFile readServerFile(String path, FileHandling.OpenOption o) throws RemoteException;
    void writeServerFile(String path, ServerFile file) throws RemoteException;
    int unlinkServerFile(String path) throws RemoteException;
}
