#!/usr/bin/env python3
# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License.  You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

import os
import sys

sys.path.append(os.path.dirname(__file__))
import argparse
import re
from configparser import ConfigParser, ExtendedInterpolation

from scriptutil import Version, find_branch_type, find_current_version, run, update_file


def update_changes(filename: str, new_version: Version, init_changes: str, headers: list[str]):
  print("  adding new section to %s..." % filename, end="", flush=True)
  matcher = re.compile(r"\d+\.\d+\.\d+\s+===")

  def edit(buffer: list[str], _match: re.Match[str], line: str):
    if new_version.dot in line:
      return None
    match = new_version.previous_dot_matcher.search(line)
    if match is not None:
      buffer.append(line.replace(match.group(0), new_version.dot))
      buffer.append(init_changes)
      for header in headers:
        buffer.append("%s\n---------------------\n(No changes)\n\n" % header)
    buffer.append(line)
    return match is not None

  changed = update_file(filename, matcher, edit)
  print("done" if changed else "uptodate")


def add_constant(new_version: Version, deprecate: bool):
  filename = "lucene/core/src/java/org/apache/lucene/util/Version.java"
  print("  adding constant %s..." % new_version.constant, end="", flush=True)
  constant_prefix = "public static final Version LUCENE_"
  matcher = re.compile(constant_prefix)
  prev_matcher = new_version.make_previous_matcher(prefix=constant_prefix, sep="_")

  def ensure_deprecated(buffer: list[str]):
    last = buffer[-1]
    if last.strip() != "@Deprecated":
      spaces = " " * (len(last) - len(last.lstrip()) - 1)
      del buffer[-1]  # Remove comment closer line
      if len(buffer) >= 4 and re.search(r"for Lucene.\s*$", buffer[-1]) is not None:
        del buffer[-3:]  # drop the trailing lines '<p> / Use this to get the latest ... / ... for Lucene.'
      buffer.append(("{0} * @deprecated ({1}) Use latest\n" + "{0} */\n" + "{0}@Deprecated\n").format(spaces, new_version))

  def buffer_constant(buffer: list[str], line: str):
    spaces = " " * (len(line) - len(line.lstrip()))
    buffer.append(("\n{0}/**\n" + "{0} * Match settings and bugs in Lucene's {1} release.\n").format(spaces, new_version))
    if deprecate:
      buffer.append("%s * @deprecated Use latest\n" % spaces)
    else:
      buffer.append(f"{spaces} * <p>Use this to get the latest &amp; greatest settings, bug fixes, etc, for Lucene.\n")
    buffer.append("%s */\n" % spaces)
    if deprecate:
      buffer.append("%s@Deprecated\n" % spaces)
    buffer.append(f"{spaces}public static final Version {new_version.constant} = new Version({new_version.major}, {new_version.minor}, {new_version.bugfix});\n")

  class Edit:
    found = -1

    def __call__(self, buffer: list[str], _match: re.Match[str], line: str):
      if new_version.constant in line:
        return None  # constant already exists
      # outer match is just to find lines declaring version constants
      match = prev_matcher.search(line)
      if match is not None:
        ensure_deprecated(buffer)  # old version should be deprecated
        self.found = len(buffer) + 1  # extra 1 for buffering current line below
      elif self.found != -1:
        # we didn't match, but we previously had a match, so insert new version here
        # first find where to insert (first empty line before current constant)
        c: list[str] = []
        buffer_constant(c, line)
        tmp = buffer[self.found :]
        buffer[self.found :] = c
        buffer.extend(tmp)
        buffer.append(line)
        return True

      buffer.append(line)
      return False

  changed = update_file(filename, matcher, Edit())
  print("done" if changed else "uptodate")


def update_build_version(new_version: Version):
  print("  changing baseVersion...", end="", flush=True)
  filename = "build-options.properties"

  def edit(buffer: list[str], _match: re.Match[str], line: str):
    if new_version.dot in line:
      return None
    buffer.append("version.base=" + new_version.dot + "\n")
    return True

  version_prop_re = re.compile(r"version\.base=(.*)")
  changed = update_file(filename, version_prop_re, edit)
  print("done" if changed else "uptodate")


def update_latest_constant(new_version: Version):
  print("  changing Version.LATEST to %s..." % new_version.constant, end="", flush=True)
  filename = "lucene/core/src/java/org/apache/lucene/util/Version.java"
  matcher = re.compile("public static final Version LATEST")

  def edit(buffer: list[str], _match: re.Match[str], line: str):
    if new_version.constant in line:
      return None
    buffer.append(line.rpartition("=")[0] + ("= %s;\n" % new_version.constant))
    return True

  changed = update_file(filename, matcher, edit)
  print("done" if changed else "uptodate")


def onerror(x: Exception):
  raise x


def check_lucene_version_tests():
  print("  checking lucene version tests...", end="", flush=True)
  run("./gradlew -p lucene/core test --tests TestVersion")
  print("ok")


def read_config(current_version: Version):
  parser = argparse.ArgumentParser(description="Add a new version to CHANGES, to Version.java and build.gradle files")
  parser.add_argument("version", type=Version.parse)
  newconf = parser.parse_args()

  newconf.branch_type = find_branch_type()
  newconf.is_latest_version = newconf.version.on_or_after(current_version)

  print("branch_type is %s " % newconf.branch_type)

  return newconf


# Hack ConfigParser, designed to parse INI files, to parse & interpolate Java .properties files
def parse_properties_file(filename: str):
  contents = open(filename, encoding="ISO-8859-1").read().replace("%", "%%")  # Escape interpolation metachar
  parser = ConfigParser(interpolation=ExtendedInterpolation())  # Handle ${property-name} interpolation
  parser.read_string("[DUMMY_SECTION]\n" + contents)  # Add required section
  return dict(parser.items("DUMMY_SECTION"))


def main():
  if not os.path.exists("build-options.properties"):
    sys.exit("Tool must be run from the root of a source checkout.")
  current_version = Version.parse(find_current_version())
  newconf = read_config(current_version)
  is_bugfix = newconf.version.is_bugfix_release()

  print("\nAdding new version %s" % newconf.version)
  # See LUCENE-8883 for some thoughts on which categories to use
  update_changes("lucene/CHANGES.txt", newconf.version, "\n", ["Bug Fixes"] if is_bugfix else ["API Changes", "New Features", "Improvements", "Optimizations", "Bug Fixes", "Other"])

  latest_or_backcompat = newconf.is_latest_version or current_version.is_back_compat_with(newconf.version)
  if latest_or_backcompat:
    add_constant(newconf.version, not newconf.is_latest_version)
  else:
    print("\nNot adding constant for version %s because it is no longer supported" % newconf.version)

  if newconf.is_latest_version:
    print("\nUpdating latest version")
    update_build_version(newconf.version)
    update_latest_constant(newconf.version)

  if newconf.version.is_major_release():
    print("\nTODO: ")
    print("  - Move backcompat oldIndexes to unsupportedIndexes in TestBackwardsCompatibility")
    print("  - Update IndexFormatTooOldException throw cases")
  elif latest_or_backcompat:
    print("\nTesting changes")
    check_lucene_version_tests()

  print()


if __name__ == "__main__":
  try:
    main()
  except KeyboardInterrupt:
    print("\nReceived Ctrl-C, exiting early")
