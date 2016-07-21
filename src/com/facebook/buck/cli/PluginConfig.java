package com.facebook.buck.cli;

import com.google.common.base.Optional;

import java.nio.file.Path;

/**
 * Created by bocon on 7/20/16.
 */
public class PluginConfig {

  private final BuckConfig delegate;

  public PluginConfig(BuckConfig delegate) {
      this.delegate = delegate;
  }

  public Optional<String> getPluginDirectory() {
    try {
      String directory = delegate.getValue("plugins", "directory").get();
      Optional<Path> path = delegate.checkPathExists(directory.replaceFirst("//", ""), "error");
      return Optional.of(path.get().toString());
    } catch (Exception e) {
      return Optional.absent();
    }
  }

}
