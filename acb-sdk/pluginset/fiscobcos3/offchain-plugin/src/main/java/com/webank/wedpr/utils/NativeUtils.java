/*
 * Copyright 2023 Ant Group
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.webank.wedpr.utils;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/**
 * Loads WeDPR native libraries from a plugin-specific path.
 *
 * <p>The upstream loader extracts every copy to {@code ~/.fisco/nativeutils}. A JVM cannot load
 * the same absolute native-library path from two PF4J class loaders, so fiscobcos2 and fiscobcos3
 * cannot otherwise run in the same plugin server.</p>
 */
public final class NativeUtils {

    private static final String NATIVE_DIRECTORY = "nativeutils-fiscobcos3";

    private NativeUtils() {
    }

    public static void loadLibrary(String resourcePath) throws IOException {
        ClassLoader contextClassLoader = Thread.currentThread().getContextClassLoader();
        ClassLoader resourceClassLoader = NativeUtils.class.getClassLoader();
        if (contextClassLoader != null && contextClassLoader.getResource(resourcePath) != null) {
            resourceClassLoader = contextClassLoader;
        }
        loadLibrary(resourcePath, resourceClassLoader);
    }

    public static boolean loadLibraryFromLibraryPath(String libraryPath, String resourcePath)
            throws IOException {
        Path source = new File(libraryPath, fileName(resourcePath)).toPath();
        if (!Files.isRegularFile(source)) {
            return false;
        }
        Path library = nativeDirectory().resolve(fileName(resourcePath));
        copyWithLock(source, library);
        System.load(library.toAbsolutePath().toString());
        return true;
    }

    public static void loadLibrary(String resourcePath, ClassLoader classLoader) throws IOException {
        String configuredLibraryPath = System.getProperty("java.library.ffipath");
        if (configuredLibraryPath != null
                && !configuredLibraryPath.isEmpty()
                && loadLibraryFromLibraryPath(configuredLibraryPath, resourcePath)) {
            return;
        }

        Path library = nativeDirectory().resolve(fileName(resourcePath));
        Path lockFile = library.getParent().resolve("native.lock");
        try (FileChannel channel = FileChannel.open(
                lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock ignored = channel.lock()) {
            try (InputStream input = classLoader.getResourceAsStream(resourcePath)) {
                if (input == null) {
                    throw new IOException("Resource not found: " + resourcePath);
                }
                Files.copy(input, library, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        System.load(library.toAbsolutePath().toString());
    }

    private static Path nativeDirectory() throws IOException {
        Path directory = new File(
                new File(System.getProperty("user.home"), ".fisco"), NATIVE_DIRECTORY).toPath();
        Files.createDirectories(directory);
        return directory;
    }

    private static void copyWithLock(Path source, Path target) throws IOException {
        Path lockFile = target.getParent().resolve("native.lock");
        try (FileChannel channel = FileChannel.open(
                lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock ignored = channel.lock()) {
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String fileName(String resourcePath) {
        int separator = resourcePath.lastIndexOf('/');
        return separator < 0 ? resourcePath : resourcePath.substring(separator + 1);
    }
}
