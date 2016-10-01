/**
 * Copyright 2014-2016 Emmanuel Keller / QWAZR
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 **/
package com.qwazr.library;

import com.qwazr.classloader.ClassFactory;
import com.qwazr.classloader.ClassLoaderManager;
import com.qwazr.utils.IOUtils;
import com.qwazr.utils.LockUtils;
import com.qwazr.utils.ReadOnlyMap;
import com.qwazr.utils.file.TrackedInterface;
import com.qwazr.utils.json.JsonMapper;
import io.undertow.security.idm.IdentityManager;
import org.apache.commons.io.FilenameUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

class LibraryManagerImpl extends ReadOnlyMap<String, LibraryInterface>
		implements LibraryManager, TrackedInterface.FileChangeConsumer, ClassFactory, Closeable {

	private static final Logger LOGGER = LoggerFactory.getLogger(LibraryManagerImpl.class);

	static volatile LibraryManagerImpl INSTANCE = null;

	static synchronized void load(final File dataDirectory, final TrackedInterface etcTracker) throws IOException {
		if (INSTANCE != null)
			throw new IOException("Already loaded");
		INSTANCE = new LibraryManagerImpl(dataDirectory, etcTracker);
		if (etcTracker != null)
			etcTracker.register(INSTANCE);
		ClassLoaderManager.getInstance().register(INSTANCE);
	}

	private final File dataDirectory;

	private final TrackedInterface etcTracker;

	private final LockUtils.ReadWriteLock mapLock = new LockUtils.ReadWriteLock();
	private final Map<File, Map<String, LibraryInterface>> libraryFileMap;

	private LibraryManagerImpl(final File dataDirectory, final TrackedInterface etcTracker) throws IOException {
		this.dataDirectory = dataDirectory;
		this.libraryFileMap = new HashMap<>();
		this.etcTracker = etcTracker;
	}

	@Override
	public void close() {
		mapLock.write(() -> {
			libraryFileMap.clear();
			IOUtils.closeObjects(this.values());
			setMap(Collections.emptyMap());
		});
	}

	final public LibraryInterface getLibrary(final String name) {
		if (etcTracker != null)
			etcTracker.check();
		return super.get(name);
	}

	final public File getDataDirectory() {
		return dataDirectory;
	}

	public Map<String, String> getLibraries() {
		if (etcTracker != null)
			etcTracker.check();
		final Map<String, String> map = new LinkedHashMap<>();
		this.forEach((name, library) -> map.put(name, library.getClass().getName()));
		return map;
	}

	final public <T> T newInstance(final Class<T> clazz) throws ReflectiveOperationException {
		return clazz == null ? null : LibraryManager.newInstance(clazz);
	}

	@Override
	public void accept(TrackedInterface.ChangeReason changeReason, File jsonFile) {
		final String extension = FilenameUtils.getExtension(jsonFile.getName());
		if (!"json".equals(extension))
			return;
		switch (changeReason) {
		case UPDATED:
			loadLibrarySet(jsonFile);
			break;
		case DELETED:
			unloadLibrarySet(jsonFile);
			break;
		}
	}

	private void loadLibrarySet(File jsonFile) {
		try {
			final LibraryConfiguration configuration;
			configuration = JsonMapper.MAPPER.readValue(jsonFile, LibraryConfiguration.class);

			if (configuration == null || configuration.library == null) {
				unloadLibrarySet(jsonFile);
				return;
			}

			if (LOGGER.isInfoEnabled())
				LOGGER.info("Load library configuration file: " + jsonFile.getAbsolutePath());

			mapLock.writeEx(() -> {
				loadLibraries(configuration.library);
				libraryFileMap.put(jsonFile, configuration.library);
				buildGlobalMap();
			});

		} catch (IOException e) {
			if (LOGGER.isErrorEnabled())
				LOGGER.error(e.getMessage(), e);
			return;
		}
	}

	private void unloadLibrarySet(File jsonFile) {
		mapLock.write(() -> {
			final Map<String, LibraryInterface> map = libraryFileMap.remove(jsonFile);
			if (map == null)
				return;
			if (LOGGER.isInfoEnabled())
				LOGGER.info("Unload library configuration file: " + jsonFile.getAbsolutePath());
			buildGlobalMap();
			IOUtils.closeObjects(map.values());
		});
	}

	private void buildGlobalMap() {
		final Map<String, LibraryInterface> libraries = new HashMap<>();
		libraryFileMap.forEach((file, libraryMap) -> libraries.putAll(libraryMap));
		setMap(libraries);
	}

	private void loadLibraries(final Map<String, LibraryInterface> libraries) {
		libraries.forEach((name, library) -> {
			try {
				library.load();
			} catch (Exception e) {
				if (LOGGER.isErrorEnabled())
					LOGGER.error(e.getMessage(), e);
			}
		});
	}

	@Override
	public IdentityManager getIdentityManager(String realm) throws IOException {
		final LibraryInterface library = get(realm);
		if (library == null)
			throw new IOException("No realm connector with this name: " + realm);
		if (!(library instanceof IdentityManager))
			throw new IOException("This is a not a realm connector: " + realm);
		return (IdentityManager) library;
	}

}
