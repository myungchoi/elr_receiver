package edu.gatech.i3l.hl7.v2.elr_receiver;

import java.io.IOException;
import java.io.InputStream;

import org.apache.commons.io.IOUtils;
import org.springframework.core.io.ByteArrayResource;

public class FileMessageResource extends ByteArrayResource {
	private final String filename;

	public FileMessageResource(final String filename, final byte[] bytes) {
		super(bytes);
		this.filename = filename;
	}
	
	public FileMessageResource(final String filename, final InputStream is) throws IOException {
		super(IOUtils.toByteArray(is));
		this.filename = filename;
	}

	@Override
	public String getFilename() {
		return filename;
	}
	
	@Override
	public boolean equals(Object obj) {
		if (obj instanceof FileMessageResource) {
			return ((FileMessageResource) obj).getFilename().equals(getFilename());
		}
		return false;
	}
	
	@Override
	public int hashCode() {
		return filename.hashCode();
	}
}
