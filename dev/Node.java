/*
 * @file:   Node.java
 * @author: Jacob Wang <tengdaw@andrew.cmu.edu>
 * LRU Node in the cache that abstractively represents a file on proxy with all the metadata needed. 
 * Each node is part of the internal doubly-linked-list that are sorted based on recency of access. 
 */

class Node {
    public String pathName;   // original path name 
    public String fileName;   // actual file name on proxy, can be different from the path name
    public int refCnt;        // count of all current accesses on the node
    public int version;       // version of the file
    public long size;         // file size
    public boolean readable;  // whether the file is only visible to the current writer or all clients
    public Node prev;         // the previous node pointer
    public Node next;         // the next node pointer

    /**
     * Constructor for a new read node in cache.
     * @param pathName  original path name
     * @param fileName  file name on proxy
     * @param version   server copy version
     * @param size      size of the file
     */
    public Node(String pathName, String fileName, int version, long size) {
        this.pathName = pathName;
        this.fileName = fileName;
        this.version = version;
        this.size = size;
        readable = true;
        refCnt = 1;
        prev = null;
        next = null;
    }

    /**
     * Constructor for a empty LRU Node
     */
    public Node() {}

    /**
     * Constructor for a new write node, called after the first write operation on the file.
     * @param node      node for the original read file
     * @param fileName  file name of the node
     * @param size      size of the file
     */
    public Node(Node node, String fileName, long size) {
        this.pathName = node.pathName;
        this.fileName = fileName;
        refCnt = 1;
        version = -1;       // no version on the server yet
        this.size = size;
        readable = false;   // only visible to the writer
        prev = null;

    }

    /**
     * Increase the inference count of the file node
     */
    public void incrRef() {
	    refCnt++;
    }

    /**
     * Decrease the inference count of the file node
     */
    public void decrRef() {
	    refCnt--;
    }

    /**
     * Check whether a file node is evictable from the cache.
     * A node is evictable if and only if there is no current operation on the file (reference count = 0).
     */
    public boolean isEvictable() {
	    return refCnt == 0;
    }

    /**
     * Update the version of the file node, used only when the server copy has an updated version.
     * @param version the updated new version
     */
    public void updateVersion(int version) {
	    this.version = version;
    }

    /**
     * Update the size of the file, called when the underlying file is written to
     * @param newSize the updated size of the file
     */
    public void updateSize(long newSize) {
	    size = newSize;
    }

    /**
     * Update the name of the file, used when close is called on a file that has been modified by a client and 
     * changes are propagated back to the server. Also update its readability as the file becomes visible to all clients.
     * @param newFileName the update name of the file
     */
    public void updateFileName(String newFileName) {
        fileName = newFileName;
        readable = true;
    }
}
