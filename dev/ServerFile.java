/* communication protocol between proxy and server */
import java.io.Serializable;

public class ServerFile implements Serializable {
    // Actual file content
    public byte[] content;
    // Whether the server file object represents a valid file
    public boolean valid;
    // Error number if the file is not valid
    public int errno;
    // Version number
    public int version;

    public ServerFile(byte[] content, int version) {
        this.content = content;
        this.version = version;
        valid = true;
    }

    public ServerFile(byte[] content) {
        this.content = content;
        valid = true;
    }

    public ServerFile(int errno) {
        this.errno = errno;
        valid = false;
    }
}
