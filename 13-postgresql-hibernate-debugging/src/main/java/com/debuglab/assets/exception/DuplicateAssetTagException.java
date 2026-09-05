package com.debuglab.assets.exception;

public class DuplicateAssetTagException extends RuntimeException {

    public DuplicateAssetTagException(String assetTag) {
        super("An asset with tag " + assetTag + " already exists");
    }
}
