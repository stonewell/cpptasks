/*
 *
 * Copyright 2002-2004 The Ant-Contrib project
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package net.sf.antcontrib.cpptasks.gcc;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Vector;

import net.sf.antcontrib.cpptasks.CCTask;
import net.sf.antcontrib.cpptasks.CUtil;
import net.sf.antcontrib.cpptasks.compiler.CommandLineCCompiler;
import net.sf.antcontrib.cpptasks.compiler.CommandLineCompilerConfiguration;
import net.sf.antcontrib.cpptasks.compiler.LinkType;
import net.sf.antcontrib.cpptasks.compiler.ProgressMonitor;

import org.apache.tools.ant.BuildException;
import org.apache.tools.ant.types.Environment;
import net.sf.antcontrib.cpptasks.OptimizationEnum;

/**
 * Abstract base class for compilers that attempt to be command line compatible
 * with GCC
 *
 * @author Adam Murdoch
 * @author Curt Arnold
 */
public abstract class GccCompatibleCCompiler extends CommandLineCCompiler {
	private final static String[] headerExtensions = new String[] { ".h", ".hpp", ".inl" };
	private final static String[] sourceExtensions = new String[] { ".c", ".cc", ".cpp", ".cxx", ".c++", ".i", ".f",
			".for" };

	/**
	 * Private constructor. Use GccCCompiler.getInstance() to get singleton instance
	 * of this class.
	 */
	protected GccCompatibleCCompiler(String command, String identifierArg, boolean libtool,
			GccCompatibleCCompiler libtoolCompiler, boolean newEnvironment, Environment env) {
		super(command, identifierArg, sourceExtensions, headerExtensions, libtool ? ".fo" : ".o", libtool,
				libtoolCompiler, newEnvironment, env);
	}

	/**
	 * Private constructor. Use GccCCompiler.getInstance() to get singleton instance
	 * of this class.
	 */
	protected GccCompatibleCCompiler(String command, String identifierArg, String[] sourceExtensions,
			String[] headerExtensions, boolean libtool, GccCompatibleCCompiler libtoolCompiler, boolean newEnvironment,
			Environment env) {
		super(command, identifierArg, sourceExtensions, headerExtensions, libtool ? ".fo" : ".o", libtool,
				libtoolCompiler, newEnvironment, env);
	}

	public void addImpliedArgs(final Vector args, final boolean debug, final boolean multithreaded,
			final boolean exceptions, final LinkType linkType, final Boolean rtti,
			final OptimizationEnum optimization) {
		//
		// -fPIC is too much trouble
		// users have to manually add it for
		// operating systems that make sense
		//
		args.addElement("-c");
		if (debug) {
			args.addElement("-g");
		} else {
			if (optimization != null) {
				if (optimization.isSize()) {
					args.addElement("-Os");
				} else if (optimization.isSpeed()) {
					if ("full".equals(optimization.getValue())) {
						args.addElement("-O2");
					} else {
						if ("speed".equals(optimization.getValue())) {
							args.addElement("-O1");
						} else {
							args.addElement("-O3");
						}
					}
				}
			}
		}
		if (getIdentifier().indexOf("mingw") >= 0) {
			if (linkType.isSubsystemConsole()) {
				args.addElement("-mconsole");
			}
			if (linkType.isSubsystemGUI()) {
				args.addElement("-mwindows");
			}
		}
		if (rtti != null && !rtti.booleanValue()) {
			args.addElement("-fno-rtti");
		}

	}

	/**
	 * Adds an include path to the command.
	 */
	public void addIncludePath(String path, Vector cmd) {
		cmd.addElement("-I" + path);
	}

	public void addWarningSwitch(Vector args, int level) {
		switch (level) {
		case 0:
			args.addElement("-w");
			break;
		case 5:
			args.addElement("-Werror");
			/* nobreak */
		case 4:
			args.addElement("-W");
			/* nobreak */
		case 3:
			args.addElement("-Wall");
			break;
		}
	}

	public void getDefineSwitch(StringBuffer buffer, String define, String value) {
		buffer.append("-D");
		buffer.append(define);
		if (value != null && value.length() > 0) {
			buffer.append('=');
			buffer.append(value);
		}
	}

	protected File[] getEnvironmentIncludePath() {
		return CUtil.getPathFromEnvironment("INCLUDE", ":");
	}

	public String getIncludeDirSwitch(String includeDir) {
		return "-I" + includeDir;
	}

	public void getUndefineSwitch(StringBuffer buffer, String define) {
		buffer.append("-U");
		buffer.append(define);
	}

	public void compile(CCTask task, File outputDir, String[] sourceFiles, String[] args, String[] endArgs,
			boolean relentless, CommandLineCompilerConfiguration config, ProgressMonitor monitor)
			throws BuildException {
		String buf = this.genMakefileBuffer(task, outputDir, sourceFiles, args, endArgs, relentless, config, monitor);

		try {
			FileOutputStream fos = new FileOutputStream(new File(outputDir, "Makefile"));

			fos.write(buf.getBytes());

			fos.close();
		} catch (Exception e) {
			throw new BuildException("Makefile generate failed", e);
		}

		int retval = runCommand(task, outputDir,
				new String[] { "make", "-j", "8", "-C", outputDir.getAbsolutePath(), "all" });
		if (monitor != null) {
			monitor.progress(sourceFiles);
		}
		//
		// if the process returned a failure code and
		// we aren't holding an exception from an earlier
		// interation
		if (retval != 0) {
			//
			// construct the exception
			//
			BuildException exc = new BuildException(this.getCommand() + " failed with return code " + retval,
					task.getLocation());
			//
			// and throw it now unless we are relentless
			//
			if (!relentless) {
				throw exc;
			}
		}
	}

	/**
	 * Build an make file buffer
	 *
	 */
	protected String genMakefileBuffer(CCTask task, File outputDir, String[] sourceFiles, String[] args,
			String[] endArgs, boolean relentless, CommandLineCompilerConfiguration config, ProgressMonitor monitor)
			throws BuildException {
		String command = getCommand();

		StringBuffer sb_opt_args = new StringBuffer();
		sb_opt_args.append("OPT_ARGS := ");
		for (int j = 0; j < args.length; j++) {
			sb_opt_args.append("\\").append(System.getProperty("line.separator")).append("\t").append(args[j]);
		}
		sb_opt_args.append(System.getProperty("line.separator"));

		StringBuffer sb_opt_end_args = new StringBuffer();
		sb_opt_end_args.append("OPT_END_ARGS := ");
		for (int j = 0; j < endArgs.length; j++) {
			sb_opt_end_args.append("\\").append(System.getProperty("line.separator")).append("\t").append(endArgs[j]);
		}
		sb_opt_end_args.append(System.getProperty("line.separator"));

		StringBuffer sb_src = new StringBuffer();
		StringBuffer sb_objs = new StringBuffer();

		sb_objs.append("OBJECTS := ");
		for (int sourceIndex = 0; sourceIndex < sourceFiles.length; sourceIndex++) {
			String[] output = getOutputFileNames(sourceFiles[sourceIndex], null);

			if (output.length == 0)
				continue;

			sb_objs.append("\\").append(System.getProperty("line.separator")).append("\t").append(output[0]);
			sb_src.append(output[0]).append(":").append(System.getProperty("line.separator")).append("\t")
					.append(getLibtool() ? "libtool " : "").append(command).append(" ").append("-pipe ")
					.append("$(OPT_ARGS) ").append("-o \"$@\" ").append("\"").append(sourceFiles[sourceIndex])
					.append("\" ").append("$(OPT_END_ARGS)").append(System.getProperty("line.separator"));
		}
		sb_src.append(System.getProperty("line.separator"));
		sb_objs.append(System.getProperty("line.separator"));

		return new StringBuffer().append(sb_opt_args).append(System.getProperty("line.separator"))
				.append(sb_opt_end_args).append(System.getProperty("line.separator")).append(sb_objs)
				.append(System.getProperty("line.separator")).append(sb_src)
				.append(System.getProperty("line.separator")).append(".PHONY: $(OBJECTS)")
				.append(System.getProperty("line.separator")).append("all:$(OBJECTS)")
				.append(System.getProperty("line.separator")).toString();
	}
}
