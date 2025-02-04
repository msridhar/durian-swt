/*
 * Copyright (C) 2020-2023 DiffPlug
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.diffplug.common.swt.os;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import java.util.function.Function;

/** Enum representing an OS and its underlying CPU architecture. */
public enum OS {
	WIN_x64, WIN_x86, LINUX_x64, LINUX_x86, MAC_x64, MAC_silicon, WIN_unknown, LINUX_unknown, MAC_unknown;

	public boolean isWindows() {
		return this == WIN_x64 || this == WIN_x86 || this == WIN_unknown;
	}

	public boolean isLinux() {
		return this == LINUX_x64 || this == LINUX_x86 || this == LINUX_unknown;
	}

	public boolean isMac() {
		return this == MAC_x64 || this == MAC_silicon || this == MAC_unknown;
	}

	public boolean isMacOrLinux() {
		return isMac() || isLinux();
	}

	/** Returns the appropriate value depending on the OS. */
	public <T> T winMacLinux(T win, T mac, T linux) {
		if (isWindows()) {
			return win;
		} else if (isMac()) {
			return mac;
		} else if (isLinux()) {
			return linux;
		} else {
			throw OS.unsupportedException(this);
		}
	}

	/** Returns the architecture of the given operating system. */
	public Arch getArch() {
		switch (this) {
		case WIN_x64:
		case LINUX_x64:
		case MAC_x64:
			return Arch.x64;
		case WIN_x86:
		case LINUX_x86:
			return Arch.x86;
		case MAC_silicon:
			return Arch.arm64;
		case WIN_unknown:
		case MAC_unknown:
		case LINUX_unknown:
			return Arch.unknown;
		default:
			throw unsupportedException(this);
		}
	}

	/** SWT-style win32/linux/macosx */
	public String os() {
		return winMacLinux("win32", "linux", "macosx");
	}

	/** SWT-style x86/x86_64 */
	public String arch() {
		return getArch().x86x64arm64unknown("x86", "x86_64", "aarch64", "unknown");
	}

	/** os().arch() */
	public String osDotArch() {
		return os() + "." + arch();
	}

	/** windowing.os.arch */
	public String toSwt() {
		return SwtPlatform.fromOS(this).toString();
	}

	/** Returns the native OS: 32-bit JVM on 64-bit Windows returns OS.WIN_64. */
	public static OS getNative() {
		detectPlatform();
		return NATIVE_OS;
	}

	/** Returns the running OS: 32-bit JVM on 64-bit Windows returns OS.WIN_32. */
	public static OS getRunning() {
		detectPlatform();
		return RUNNING_OS;
	}

	/** Eagerly detects the native and running JVM properties. */
	public static void detectPlatform(Function<String, String> systemProperty, Function<String, String> environmentVariable) {
		if (NATIVE_OS == null) {
			NATIVE_OS = calculateNative(systemProperty, environmentVariable);
			RUNNING_OS = calculateRunning(systemProperty);
		}
	}

	private static void detectPlatform() {
		if (NATIVE_OS == null) {
			detectPlatform(System::getProperty, System::getenv);
		}
	}

	private static OS NATIVE_OS;

	/** Calculates the native OS. */
	private static OS calculateNative(Function<String, String> systemProperty, Function<String, String> environmentVariable) {
		String os_name = systemProperty.apply("os.name").toLowerCase(Locale.getDefault());
		boolean isWin = os_name.contains("win");
		boolean isMac = os_name.contains("mac");
		boolean isLinux = Arrays.asList("nix", "nux", "aix").stream().anyMatch(os_name::contains);
		if (isMac) {
			return exec("uname", "-a").contains("_ARM64_") ? MAC_silicon : MAC_x64;
		} else if (isWin) {
			boolean is64bit = environmentVariable.apply("ProgramFiles(x86)") != null;
			return is64bit ? WIN_x64 : WIN_x86;
		} else if (isLinux) {
			String os_arch = systemProperty.apply("os.arch");
			switch (os_arch) {
			case "i386":
			case "x86":
				return LINUX_x86;
			case "x86_64":
			case "amd64":
				return LINUX_x64;
			default:
				return LINUX_unknown;
			}
		} else {
			throw new IllegalArgumentException("Unknown os.name '" + os_name + "'.");
		}
	}

	private static String exec(String... cmd) {
		try {
			Process process = Runtime.getRuntime().exec(cmd);
			ByteArrayOutputStream output = new ByteArrayOutputStream();
			drain(process.getInputStream(), output);
			return new String(output.toByteArray(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	private static void drain(InputStream input, OutputStream output) throws IOException {
		byte[] buf = new byte[1024];
		int numRead;
		while ((numRead = input.read(buf)) != -1) {
			output.write(buf, 0, numRead);
		}
	}

	private static OS RUNNING_OS;

	/** Calculates the running OS. */
	private static OS calculateRunning(Function<String, String> systemProperty) {
		Arch runningArch = runningJvm(systemProperty);
		return NATIVE_OS.winMacLinux(
				runningArch.x86x64arm64unknown(OS.WIN_x86, OS.WIN_x64, OS.WIN_unknown, OS.WIN_unknown),
				runningArch.x86x64arm64unknown(OS.MAC_unknown, OS.MAC_x64, OS.MAC_silicon, OS.MAC_unknown),
				runningArch.x86x64arm64unknown(OS.LINUX_x86, OS.LINUX_x64, OS.LINUX_unknown, OS.LINUX_unknown));
	}

	/** Returns the arch of the currently running JVM. */
	private static Arch runningJvm(Function<String, String> systemProperty) {
		String sunArchDataModel = systemProperty.apply("sun.arch.data.model");
		switch (sunArchDataModel) {
		case "32":
			return Arch.x86;
		case "64":
			return "aarch64".equals(systemProperty.apply("os.arch")) ? Arch.arm64 : Arch.x64;
		default:
			return Arch.unknown;
		}
	}

	/** Returns an UnsupportedOperationException for the given OS. */
	public static UnsupportedOperationException unsupportedException(OS os) {
		return new UnsupportedOperationException("Operating system '" + os + "' is not supported.");
	}

	public static void main(String[] args) {
		System.out.println("native=" + OS.getNative());
		System.out.println("running=" + OS.getRunning());
	}
}
