/*
 * Copyright 2002-2011 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use
 * this file except in compliance with the License. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed
 * under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
 * CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */

package org.nodex.core.file;

import org.nodex.core.BackgroundTask;
import org.nodex.core.BackgroundTaskWithResult;
import org.nodex.core.Completion;
import org.nodex.core.CompletionWithResult;
import org.nodex.core.Nodex;
import org.nodex.core.NodexInternal;
import org.nodex.core.buffer.Buffer;
import org.nodex.core.streams.ReadStream;
import org.nodex.core.streams.WriteStream;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.AsynchronousFileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileStore;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotLinkException;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

public class FileSystem {

  public static FileSystem instance = new FileSystem();

  private FileSystem() {
  }

  public void copy(String from, String to, Completion completion) {
    copy(from, to, false, completion);
  }

  public void copy(String from, String to, final boolean recursive, Completion completion) {
    final Path source = Paths.get(from);
    final Path target = Paths.get(to);
    new BackgroundTask(completion) {
      public Object execute() throws Exception {
        try {
          if (recursive) {
            Files.walkFileTree(source, EnumSet.of(FileVisitOption.FOLLOW_LINKS), Integer.MAX_VALUE,
             new SimpleFileVisitor<Path>() {
               public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
                   throws IOException {
                 Path targetDir = target.resolve(source.relativize(dir));
                 try {
                   Files.copy(dir, targetDir);
                 } catch (FileAlreadyExistsException e) {
                    if (!Files.isDirectory(targetDir)) {
                      throw e;
                    }
                 }
                 return FileVisitResult.CONTINUE;
               }
               @Override
               public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                   throws IOException {
                   Files.copy(file, target.resolve(source.relativize(file)));
                   return FileVisitResult.CONTINUE;
               }
             });
          } else {
            Files.copy(source, target);
          }
        } catch (FileAlreadyExistsException e) {
          throw new FileSystemException("File already exists " + e.getMessage());
        }
        return null;
      }
    }.run();
  }

  public void move(String from, String to, Completion completion) {
    //TODO atomic moves - but they have different semantics, e.g. on Linux if target already exists it is overwritten
    final Path source = Paths.get(from);
    final Path target = Paths.get(to);
    new BackgroundTask(completion) {
      public Object execute() throws Exception {
        try {
          Files.move(source, target);
        } catch (FileAlreadyExistsException e) {
          throw new FileSystemException("Failed to move between " + source + " and " + target + ". Target already exists");
        } catch (AtomicMoveNotSupportedException e) {
          throw new FileSystemException("Atomic move not supported between " + source + " and " + target);
        }
        return null;
      }
    }.run();
  }

  public void truncate(final String path, final long len, Completion completion) {
    new BackgroundTask(completion) {
      public Object execute() throws Exception {
        if (len < 0) {
          throw new FileSystemException("Cannot truncate file to size < 0");
        }
        if (!Files.exists(Paths.get(path))) {
          throw new FileSystemException("Cannot truncate file " + path + ". Does not exist");
        }

        RandomAccessFile raf = null;
        try {
          raf = new RandomAccessFile(path, "rw");
          raf.getChannel().truncate(len);
        } catch (FileNotFoundException e) {
          throw new FileSystemException("Cannot open file " + path + ". Either it is a directory or you don't have permission to change it");
        } finally {
          if (raf != null) raf.close();
        }
        return null;
      }
    }.run();
  }

  public void chmod(String path, String perms, Completion completion) {
    chmod(path, perms, null, completion);
  }

  /*
  Permissions is a String of the form rwxr-x---
  See http://download.oracle.com/javase/7/docs/api/java/nio/file/attribute/PosixFilePermissions.html fromString method
   */
  public void chmod(String path, String perms, String dirPerms, Completion completion) {
    final Path target = Paths.get(path);
    final Set<PosixFilePermission> permissions = PosixFilePermissions.fromString(perms);
    final Set<PosixFilePermission> dirPermissions = dirPerms == null ? null : PosixFilePermissions.fromString(dirPerms);
    new BackgroundTask(completion) {
      public Object execute() throws Exception {
        try {
          if (dirPermissions != null) {
            Files.walkFileTree(target, new SimpleFileVisitor<Path>() {
             public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
               //The directory entries typically have different permissions to the files, e.g. execute permission
               //or can't cd into it
               Files.setPosixFilePermissions(dir, dirPermissions);
               return FileVisitResult.CONTINUE;
             }
             public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
               Files.setPosixFilePermissions(file, permissions);
               return FileVisitResult.CONTINUE;
             }
           });
          } else {
            Files.setPosixFilePermissions(target, permissions);
          }
        } catch (SecurityException e) {
          throw new FileSystemException("Accessed denied for chmod on " + target);
        }
        return null;
      }
    }.run();
  }

  public void stat(String path,  CompletionWithResult<FileStats> completion) {
    stat(path, true, completion);
  }

  public void lstat(String path, CompletionWithResult<FileStats> completion) {
    stat(path, false, completion);
  }

  private void stat(String path, final boolean followLinks, CompletionWithResult<FileStats> completion) {
    final Path target = Paths.get(path);
    new BackgroundTaskWithResult<FileStats>(completion) {
      public FileStats execute() throws Exception {
        BasicFileAttributes attrs;
        if (followLinks) {
          attrs = Files.readAttributes(target, BasicFileAttributes.class);
        } else {
          attrs = Files.readAttributes(target, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        }
        return new FileStats(attrs);
      }
    }.run();
  }

  public void link(String link, String existing, Completion completion) {
    link(link, existing, false, completion);
  }

  public void symlink(String link, String existing, Completion completion) {
    link(link, existing, true, completion);
  }

  private void link(String link, String existing, final boolean symbolic, Completion completion) {
    final Path source = Paths.get(link);
    final Path target = Paths.get(existing);
    new BackgroundTask(completion) {
      public Object execute() throws Exception {
        try {
          if (symbolic) {
            Files.createSymbolicLink(source, target);
          } else {
            Files.createLink(source, target);
          }
        } catch (FileAlreadyExistsException e) {
          throw new FileSystemException("Cannot create link, file already exists: " + source);
        }
        return null;
      }
    }.run();
  }

  public void unlink(String link, Completion completion) {
    delete(link, completion);
  }

  public void readSymlink(String link, CompletionWithResult<String> completion) {
    final Path source = Paths.get(link);
    new BackgroundTaskWithResult<String>(completion) {
      public String execute() throws Exception {
        try {
          return Files.readSymbolicLink(source).toString();
        } catch (NotLinkException e) {
          throw new FileSystemException("Cannot read " + source + " it's not a symbolic link");
        }
      }
    }.run();
  }

  public void delete(String path, Completion completion) {
    delete(path, false, completion);
  }

  public void delete(String path, final boolean recursive, Completion completion) {
    final Path source = Paths.get(path);
    new BackgroundTask(completion) {
      public Object execute() throws Exception {
        if (recursive) {
           Files.walkFileTree(source, new SimpleFileVisitor<Path>() {
             public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
               Files.delete(file);
               return FileVisitResult.CONTINUE;
             }

             public FileVisitResult postVisitDirectory(Path dir, IOException e) throws IOException {
               if (e == null) {
                 Files.delete(dir);
                 return FileVisitResult.CONTINUE;
               } else {
                 throw e;
               }
             }
         });
        } else {
          try {
            Files.delete(source);
          } catch (NoSuchFileException e) {
            throw new FileSystemException("Cannot delete file, it does not exist: " + source);
          } catch (DirectoryNotEmptyException e) {
            throw new FileSystemException("Cannot delete directory, it is not empty: " + source + ". Use recursive delete");
          }
        }
        return null;
      }
    }.run();
  }

  public void mkdir(String path, String perms, final boolean createParents, Completion completion) {
    final Path source = Paths.get(path);
    final FileAttribute<?> attrs = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString(perms));
    new BackgroundTask(completion) {
      public Object execute() throws Exception {

        try {
          if (createParents) {
            Files.createDirectories(source, attrs);
          } else {
            Files.createDirectory(source, attrs);
          }
        } catch (FileAlreadyExistsException e) {
          throw new FileSystemException("Cannot create directory: " + source + ". It already exists");
        }
        return null;
      }
    }.run();
  }

  public void readDir(final String path, CompletionWithResult<String[]> completion) {
    readDir(path, null, completion);
  }

  public void readDir(final String path, final String filter, CompletionWithResult<String[]> completion) {
    new BackgroundTaskWithResult<String[]>(completion) {
      public String[] execute() throws Exception {
        File file = new File(path);
        if (!file.exists()) {
          throw new FileSystemException("Cannot read directory " + path + ". Does not exist");
        }
        if (!file.isDirectory()) {
          throw new FileSystemException("Cannot read directory " + path + ". It's not a directory");
        } else {
          FilenameFilter fnFilter;
          if (filter != null) {
            fnFilter = new FilenameFilter() {
              public boolean accept(File dir, String name) {
                return Pattern.matches(filter, name);
              }
            };
          } else {
            fnFilter = null;
          }
          File[] files = file.listFiles(fnFilter);
          String[] ret = new String[files.length];
          int i = 0;
          for (File f: files) {
            ret[i++] = f.getCanonicalPath();
          }
          return ret;
        }
      }
    }.run();
  }


  // Close and open

  public void open(final String path,
                   CompletionWithResult<FileHandle> completion) {
    open(path, null, true, true, true, false, false, completion);
  }

  public void open(final String path, String perms,
                   CompletionWithResult<FileHandle> completion) {
    open(path, perms, true, true, true, false, false, completion);
  }

  public void open(final String path, String perms, final boolean createNew,
                   CompletionWithResult<FileHandle> completion) {
    open(path, perms, true, true, createNew, false, false, completion);
  }

  public void open(final String path, String perms, final boolean read, final boolean write, final boolean createNew,
                   CompletionWithResult<FileHandle> completion) {
    open(path, perms, read, write, createNew, false, false, completion);
  }

  public void open(final String path, final String perms, final boolean read, final boolean write, final boolean createNew,
                   final boolean sync, final boolean syncMeta, CompletionWithResult<FileHandle> completion) {
    final String contextID = Nodex.instance.getContextID();
    new BackgroundTaskWithResult<FileHandle>(completion) {
      public FileHandle execute() throws Exception {
        return doOpen(path, perms, read, write, createNew, sync, syncMeta, contextID);
      }
    }.run();
  }

  private FileHandle doOpen(final String path, String perms, final boolean read, final boolean write, final boolean createNew,
                   final boolean sync, final boolean syncMeta, final String contextID) throws Exception {
    if (!read && !write) {
      throw new FileSystemException("Cannot open file for neither reading nor writing");
    }
    Path file = Paths.get(path);
    HashSet<OpenOption> options = new HashSet<>();
    if (read) options.add(StandardOpenOption.READ);
    if (write) options.add(StandardOpenOption.WRITE);
    if (createNew) options.add(StandardOpenOption.CREATE_NEW);
    if (sync) options.add(StandardOpenOption.DSYNC);
    if (syncMeta) options.add(StandardOpenOption.SYNC);
    FileAttribute<?> attrs = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString(perms));

    AsynchronousFileChannel chann = AsynchronousFileChannel.open(file, options, NodexInternal.instance.getBackgroundPool(), attrs);
    return new FileHandle(chann, contextID);
  }

  public void close(final FileHandle fh, Completion completion) {
    fh.checkContext();
    new BackgroundTask(completion) {
      public FileHandle execute() throws Exception {
        fh.close();
        return null;
      }
    }.run();
  }


  // Random access

  public void write(FileHandle fh, Buffer buffer, int position, final Completion completion) {
    fh.checkContext();
    fh.write(buffer, position, completion);
  }

  public void read(FileHandle fh, Buffer buffer, int position, int bytesToRead, CompletionWithResult<Buffer> completion) {
    fh.checkContext();
    fh.read(position, bytesToRead, completion);
  }

  // Read and write entire files in one go

  public void readFile(final String path, CompletionWithResult<Buffer> completion) {
    new BackgroundTaskWithResult<Buffer>(completion) {
      public Buffer execute() throws Exception {
        Path target = Paths.get(path);
        byte[] bytes = Files.readAllBytes(target);
        Buffer buff = Buffer.newWrapped(bytes);
        return buff;
      }
    }.run();
  }

  public void writeFile(String path, String str, Completion completion) {
    writeFile(path, Buffer.fromString(str), completion);
  }

  public void writeFile(final String path, final Buffer data, Completion completion) {
    new BackgroundTask(completion) {
      public Object execute() throws Exception {
        Path target = Paths.get(path);
        Files.write(target, data._toChannelBuffer().array());
        return null;
      }
    }.run();
  }

  public void lock() {
    //TODO
  }

  public void unlock() {
    //TODO
  }

  public void watchFile() {
    //TODO
  }

  public void unwatchFile() {
    //TODO
  }

  public void createReadStream(final String path, final CompletionWithResult<ReadStream> completion) {
    final String contextID = Nodex.instance.getContextID();
    new BackgroundTaskWithResult<ReadStream>(completion) {
      public ReadStream execute() throws Exception {
        FileHandle fh = doOpen(path, null, true, false, false, false, false, contextID);
        return fh.getReadStream();
      }
    }.run();
  }

  public void createWriteStream(final String path, final CompletionWithResult<WriteStream> completion) {
    createWriteStream(path, false, completion);
  }

  public void createWriteStream(final String path, final boolean sync, final CompletionWithResult<WriteStream> completion) {
    final String contextID = Nodex.instance.getContextID();
    new BackgroundTaskWithResult<WriteStream>(completion) {
      public WriteStream execute() throws Exception {

        //doOpen(final String path, String perms, final boolean read, final boolean write, final boolean createNew,
        //           final boolean sync, final boolean syncMeta, final String contextID) throws Exception {

        FileHandle fh = doOpen(path, null, false, true, true, sync, false, contextID);
        return fh.getWriteStream();
      }
    }.run();
  }

  //Create an empty file
  public void createFile(final String path, final String perms, Completion completion) {
    final FileAttribute<?> attrs = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString(perms));
    new BackgroundTask(completion) {
      public Object execute() throws Exception {
        try {
          Path target = Paths.get(path);
          Files.createFile(target, attrs);
        } catch (FileAlreadyExistsException e) {
          throw new FileSystemException("Cannot create link, file already exists: " + path);
        }
        return null;
      }
    }.run();
  }

  public void exists(final String path, CompletionWithResult<Boolean> completion) {
    new BackgroundTaskWithResult<Boolean>(completion) {
      public Boolean execute() throws Exception {
        File file = new File(path);
        return file.exists();
      }
    }.run();
  }

  public void getFSStats(final String path, CompletionWithResult<FileSystemStats> completion) {
    new BackgroundTaskWithResult<FileSystemStats>(completion) {
      public FileSystemStats execute() throws Exception {
        Path target = Paths.get(path);
        FileStore fs = Files.getFileStore(target);
        return new FileSystemStats(fs.getTotalSpace(), fs.getUnallocatedSpace(), fs.getUsableSpace());
      }
    }.run();
  }


  public void sync(final FileHandle fh, final boolean metaData, Completion completion) {
    fh.checkContext();
    new BackgroundTask(completion) {
      public Object execute() throws Exception {
        fh.sync(metaData);
        return null;
      }
    }.run();
  }



}
