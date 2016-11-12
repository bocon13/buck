/*
 * Copyright 2016-present Facebook, Inc.
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

package com.facebook.buck.rules.macros;

import com.facebook.buck.jvm.java.HasClasspathEntries;
import com.facebook.buck.model.MacroException;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.SourcePathResolver;
import com.google.common.base.Optional;

import java.nio.file.Path;

/**
 * Resolves to the binary directory (e.g. classes directory) for a build target.
 */
public class BinDirectoryMacroExpander extends BuildTargetMacroExpander {
  @Override
  public String expand(SourcePathResolver resolver, BuildRule rule)
      throws MacroException {
    if (rule instanceof HasClasspathEntries) {
      Optional<Path> dir = ((HasClasspathEntries) rule).getClassesDirectory();
      if (dir.isPresent()) {
        return dir.get().toAbsolutePath().toString();
      }
    }
//    if (rule instanceof JavaTest) {
//      rule = ((JavaTest) rule).getCompiledTestsLibrary();
//    }
//    if (rule instanceof DefaultJavaLibrary) {
//      return DefaultJavaLibrary.getClassesDir(rule.getBuildTarget(), rule.getProjectFilesystem())
//          .toAbsolutePath().toString();
//    }
    throw new MacroException(String.format(
        "%s used in bin directory macro is of unsupported type: %s",
        rule.getBuildTarget(),
        rule.getType()));
  }
}
