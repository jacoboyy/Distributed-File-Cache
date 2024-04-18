/*
 * @file:   ServerFile.java
 * @author: Jacob Wang <tengdaw@andrew.cmu.edu>
 * Define the file and meta-data transmission protocol from server to proxy
 */

import java.io.Serializable;

public class ServerFile implements Serializable {
  public byte[] content; // Chunked file content
  public boolean valid; // Whether the server file object represents a valid file
  public int errno; // Error number for invalid operations
  public int version; // File version on server
  public long fileSize; // size of file

  /**
   * @brief Constructor for a valid file on server
   * @param content  the chunked content of the file
   * @param version  the file version on the server
   * @param fileSize the size of the file
   */
  public ServerFile(byte[] content, int version, long fileSize) {
    this.content = content;
    this.version = version;
    this.fileSize = fileSize;
    valid = true;
  }

  /**
   * @brief Constructor used when the operation to read server file is invalid
   * @param errno associated error number
   */
  public ServerFile(int errno) {
    this.errno = errno;
    valid = false;
  }
}
