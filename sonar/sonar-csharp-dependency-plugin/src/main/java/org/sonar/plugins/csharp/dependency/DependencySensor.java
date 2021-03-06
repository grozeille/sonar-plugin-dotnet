/*
 * Sonar C# Plugin :: Dependency
 * Copyright (C) 2010 Jose Chillan, Alexandre Victoor and SonarSource
 * dev@sonar.codehaus.org
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02
 */

package org.sonar.plugins.csharp.dependency;

import java.io.File;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sonar.api.batch.DependsUpon;
import org.sonar.api.batch.SensorContext;
import org.sonar.api.resources.Project;
import org.sonar.api.resources.ProjectFileSystem;
import org.sonar.api.utils.SonarException;
import org.sonar.dotnet.tools.commons.utils.FileFinder;
import org.sonar.dotnet.tools.dependencyparser.DependencyParserCommandBuilder;
import org.sonar.dotnet.tools.dependencyparser.DependencyParserException;
import org.sonar.dotnet.tools.dependencyparser.DependencyParserRunner;
import org.sonar.plugins.csharp.api.CSharpConfiguration;
import org.sonar.plugins.csharp.api.CSharpConstants;
import org.sonar.plugins.csharp.api.MicrosoftWindowsEnvironment;
import org.sonar.plugins.csharp.api.sensor.AbstractCSharpSensor;
import org.sonar.plugins.csharp.dependency.results.DependencyResultParser;

@DependsUpon(CSharpConstants.CSHARP_CORE_EXECUTED)
public class DependencySensor extends AbstractCSharpSensor {

  private static final Logger LOG = LoggerFactory.getLogger(DependencySensor.class);

  private CSharpConfiguration configuration;

  private ProjectFileSystem fileSystem;

  private DependencyResultParser dependencyParserResultParser;

  public DependencySensor(ProjectFileSystem fileSystem, MicrosoftWindowsEnvironment microsoftWindowsEnvironment, CSharpConfiguration configuration, DependencyResultParser dependencyParserResultParser) {
    super(microsoftWindowsEnvironment, "DependencyParser", configuration.getString(DependencyConstants.MODE, ""));
    this.configuration = configuration;
    this.dependencyParserResultParser = dependencyParserResultParser;
    this.fileSystem = fileSystem;
  }

  public void analyse(Project project, SensorContext context) {

    dependencyParserResultParser.setContext(context);
    dependencyParserResultParser.setEncoding(fileSystem.getSourceCharset());
    dependencyParserResultParser.setProject(project);

    final File reportFile;
    File projectDir = project.getFileSystem().getBasedir();
    String reportDefaultPath = getMicrosoftWindowsEnvironment().getWorkingDirectory() + "/" + DependencyConstants.DEPENDENCYPARSER_REPORT_XML;

    if (MODE_REUSE_REPORT.equalsIgnoreCase(executionMode)) {
      String reportPath = configuration.getString(DependencyConstants.REPORTS_PATH_KEY, reportDefaultPath);
      reportFile = FileFinder.browse(projectDir, reportPath);
      LOG.info("Reusing DependencyParser report: " + reportFile);
    } else {
      // run DependencyParser
      try {
        File tempDir = new File(getMicrosoftWindowsEnvironment().getCurrentSolution().getSolutionDir(), getMicrosoftWindowsEnvironment()
            .getWorkingDirectory());
        DependencyParserRunner runner = DependencyParserRunner.create(
            configuration.getString(DependencyConstants.INSTALL_DIR_KEY, ""), tempDir.getAbsolutePath());

        launchDependencyParser(project, runner);
      } catch (DependencyParserException e) {
        throw new SonarException("DependencyParser execution failed.", e);
      }
      reportFile = new File(projectDir, reportDefaultPath);
    }

    // and analyse results
    analyseResults(reportFile);
  }

  private void analyseResults(File reportFile) {
    if (reportFile.exists()) {
      LOG.debug("DependencyParser report found at location {}", reportFile);
      dependencyParserResultParser.parse(reportFile);
    } else {
      LOG.warn("No DependencyParser report found for path {}", reportFile);
    }
  }

  private void launchDependencyParser(Project project, DependencyParserRunner runner) throws DependencyParserException {
    DependencyParserCommandBuilder builder = runner.createCommandBuilder(getVSSolution(), getVSProject(project));
    builder.setReportFile(new File(fileSystem.getSonarWorkingDirectory(), DependencyConstants.DEPENDENCYPARSER_REPORT_XML));
    builder.setBuildConfigurations(configuration.getString(CSharpConstants.BUILD_CONFIGURATIONS_KEY,
        CSharpConstants.BUILD_CONFIGURATIONS_DEFVALUE));

    // String[] assemblies = configuration.getStringArray("sonar.gendarme.assemblies");
    // if (assemblies == null || assemblies.length == 0) {
    // assemblies = configuration.getStringArray(CSharpConstants.ASSEMBLIES_TO_SCAN_KEY);
    // } else {
    // LOG.warn("Using deprecated key 'sonar.gendarme.assemblies', you should use instead " + CSharpConstants.ASSEMBLIES_TO_SCAN_KEY);
    // }
    //
    // builder.setAssembliesToScan(assemblies);

    runner.execute(builder, configuration.getInt(DependencyConstants.TIMEOUT_MINUTES_KEY, DependencyConstants.TIMEOUT_MINUTES_DEFVALUE));
  }

  public boolean shouldExecuteOnProject(Project project) {
    if (project.isRoot()) {
      return false;
    }
    boolean skipMode = MODE_SKIP.equalsIgnoreCase(executionMode);
    if (skipMode) {
      LOG.info("{} plugin won't execute as it is set to 'skip' mode.", toolName);
      return false;
    }

    return CSharpConstants.LANGUAGE_KEY.equals(project.getLanguageKey());
  }

}
