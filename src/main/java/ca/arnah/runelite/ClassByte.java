package ca.arnah.runelite;

public class ClassByte {
	public String name;
	public byte[] bytes;
	public boolean resource;
	public ClassByte(byte[] bytes, String name, boolean resource) {
		this.name = name;
		this.bytes = bytes;
		this.resource = resource;
	}
	public ClassByte(byte[] bytes, String name) {
		this(bytes, name, false);
	}
}
