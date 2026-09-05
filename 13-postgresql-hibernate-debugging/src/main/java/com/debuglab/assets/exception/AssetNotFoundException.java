package com.debuglab.assets.exception;

public class AssetNotFoundException extends RuntimeException {

    public AssetNotFoundException(Long id) {
        super("No asset exists with id " + id);
    }
}
