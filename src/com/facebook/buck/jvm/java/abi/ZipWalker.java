/*
 * Copyright 2014-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.facebook.buck.jvm.java.abi;

import com.google.common.base.Preconditions;
import com.google.common.collect.Sets;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Enumeration;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * A {@link Walker} which iterates over entries of a ZIP file in sorted (name) order.
 */
class ZipWalker implements Walker {
  private final Path zipFile;

  public ZipWalker(Path path) {
    this.zipFile = Preconditions.checkNotNull(path);
  }

  @Override
  public void walk(FileAction onFile) throws IOException {
    Set<String> names = Sets.newTreeSet();

    if (zipFile.toFile().length() == 0) {
      return;
    }

    try (ZipFile zip = new ZipFile(zipFile.toFile())) {
      // Get the set of all names and sort them, so that we get a deterministic iteration order.
      Enumeration<? extends ZipEntry> entries = zip.entries();
      while (entries.hasMoreElements()) {
        ZipEntry entry = entries.nextElement();
        if (entry.isDirectory()) {
          continue;
        }
        names.add(entry.getName());
      }

      // Iterate over the file entries, calling the action on each one.
      for (String name : names) {
        onFile.visit(Paths.get(name), zip.getInputStream(zip.getEntry(name)));
      }
    }
  }
}

