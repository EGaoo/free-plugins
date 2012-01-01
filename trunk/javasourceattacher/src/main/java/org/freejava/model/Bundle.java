package org.freejava.model;

import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;

@Entity
public class Bundle {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String md5First1024Bytes; // to avoid downloading whole file to calculate md5
    private Long fileSize;

    private String md5; // may be null if md5First1024Bytes and fileSize are used
    private String sha1;

    private Long sourceId; // ID of corresponding source bundle


    // Accessors for the fields.  JPA doesn't use these, but your application does.

    public Long getId() {
        return id;
    }
    public void setId(Long id) {
        this.id = id;
    }

	public String getMd5() {
		return md5;
	}
	public void setMd5(String md5) {
		this.md5 = md5;
	}

	public String getSha1() {
		return sha1;
	}
	public void setSha1(String sha1) {
		this.sha1 = sha1;
	}

	public String getMd5First1024Bytes() {
		return md5First1024Bytes;
	}
	public void setMd5First1024Bytes(String md5First1024Bytes) {
		this.md5First1024Bytes = md5First1024Bytes;
	}

	public Long getFileSize() {
		return fileSize;
	}
	public void setFileSize(Long fileSize) {
		this.fileSize = fileSize;
	}

	public Long getSourceId() {
		return sourceId;
	}
	public void setSourceId(Long sourceId) {
		this.sourceId = sourceId;
	}

}