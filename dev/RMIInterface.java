/*
 * @file:   RMIInterface.java
 * @author: Jacob Wang <tengdaw@andrew.cmu.edu>
 * Define the RMI interface for RPC calls between the proxy and the server
 */

import java.rmi.Remote;
import java.rmi.RemoteException;

interface RMIInterface extends Remote {
  // RPC call that reads a file on server
  ServerFile readServerFile(String path, FileHandling.OpenOption o, int version, int offset)
      throws RemoteException;
  // RPC call that write to a file on server
  int writeServerFile(String path, byte[] content, int offset) throws RemoteException;
  // RPC call to delete a file on server
  int unlinkServerFile(String path) throws RemoteException;
}
