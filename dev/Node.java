/* LRU Node in the cache*/

class Node {
    public String pathName;
    public String fileName;
    public int refCnt;
    public int version;
    public long size;
    public boolean readable;
    public Node prev;
    public Node next;

    public Node(String pathName, String fileName, int refCnt, int version, long size) {
        this.pathName = pathName;
        this.fileName = fileName;
        this.refCnt = refCnt;
        this.version = version;
        this.size = size;
        readable = true;
        prev = null;
        next = null;
    }

    public Node() {
        prev = null;
        next = null;
    }

    public Node(Node node, String fileName, long size) {
        pathName = node.pathName;
        this.fileName = fileName;
        refCnt = 1;
        version = -1;
        this.size = size;
        readable = false;
    }

    public void incrRef() {
        refCnt++;
    }

    public void decrRef() {
        refCnt--;
    }

    public boolean isEvictable() {
        return refCnt == 0;
    }

    public boolean isReadable() {
        return readable;
    }

    public void updateVersion(int version) {
        this.version = version;
        readable = true;
    }

    public void updateSize(long newSize) {
        size = newSize;
    }

    public long getFileSize() {
        return size;
    }
}