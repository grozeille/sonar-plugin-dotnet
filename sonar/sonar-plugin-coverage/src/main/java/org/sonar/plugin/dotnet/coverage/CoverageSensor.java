/**
 * Maven and Sonar plugin for .Net
 * Copyright (C) 2010 Jose Chillan and Alexandre Victoor
 * mailto: jose.chillan@codehaus.org or alexvictoor@codehaus.org
 *
 * Sonar is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * Sonar is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with Sonar; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02
 */

/*
 * Created on May 14, 2009
 */
package org.sonar.plugin.dotnet.coverage;

import static org.sonar.plugin.dotnet.core.Constant.SONAR_EXCLUDE_GEN_CODE_KEY;
import static org.sonar.plugin.dotnet.coverage.Constants.*;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.maven.dotnet.commons.GeneratedCodeFilter;
import org.apache.maven.dotnet.commons.project.DotNetProjectException;
import org.apache.maven.dotnet.commons.project.VisualStudioProject;
import org.apache.maven.dotnet.commons.project.VisualStudioSolution;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sonar.api.batch.SensorContext;
import org.sonar.api.batch.maven.MavenPluginHandler;
import org.sonar.api.measures.CoreMetrics;
import org.sonar.api.measures.Measure;
import org.sonar.api.measures.PersistenceMode;
import org.sonar.api.measures.PropertiesBuilder;
import org.sonar.api.resources.Project;
import org.sonar.api.resources.Resource;
import org.sonar.api.utils.ParsingUtils;
import org.sonar.plugin.dotnet.core.AbstractDotnetSensor;
import org.sonar.plugin.dotnet.core.project.VisualUtils;
import org.sonar.plugin.dotnet.core.resource.CSharpFile;
import org.sonar.plugin.dotnet.core.resource.CSharpFileLocator;
import org.sonar.plugin.dotnet.core.resource.CSharpFolder;
import org.sonar.plugin.dotnet.coverage.model.Coverable;
import org.sonar.plugin.dotnet.coverage.model.FileCoverage;
import org.sonar.plugin.dotnet.coverage.model.FolderCoverage;
import org.sonar.plugin.dotnet.coverage.model.ParserResult;
import org.sonar.plugin.dotnet.coverage.model.ProjectCoverage;
import org.sonar.plugin.dotnet.coverage.model.SourceLine;
import org.sonar.plugin.dotnet.coverage.stax.CoverageResultStaxParser;

/**
 * Collects the results from a PartCover report. Most of the work is delegate
 * to {@link CoverageResultStaxParser}.
 * 
 * @author Jose CHILLAN May 14, 2009,
 *  updated for Stax version by Maxime SCHNEIDER-DUFEUTRELLE January 26, 2011
 */
public class CoverageSensor extends AbstractDotnetSensor {
  
  private final static Logger log = LoggerFactory.getLogger(CoverageSensor.class);
  
  private final PropertiesBuilder<String, Integer> lineHitsBuilder = new PropertiesBuilder<String, Integer>(
      CoreMetrics.COVERAGE_LINE_HITS_DATA);
  
  private CoveragePluginHandler pluginHandler;
  private CoverageResultStaxParser staxParser;
  
  /**
   * Constructs the collector Constructs a @link{PartCoverCollector}.
   */
  public CoverageSensor(CoveragePluginHandler pluginHandler, CoverageResultStaxParser staxParser) {
    this.pluginHandler = pluginHandler;
    this.staxParser = staxParser;
  }

  /**
   * Proceeds to the analysis.
   * 
   * @param project
   * @param context
   */
  @Override
  public void analyse(Project project, SensorContext context) {

    final String reportFileName;
    if (COVERAGE_REUSE_MODE.equals(getCoverageMode(project))) {
      reportFileName = project.getConfiguration()
          .getString(COVERAGE_REPORT_KEY);
      log.warn("Using reuse report mode for the dotnet coverage plugin");
    } else {
      reportFileName = COVERAGE_REPORT_XML;
    }

    File dir = getReportsDirectory(project);
    File report = new File(dir, reportFileName);

    if (!report.exists()) {
      log.info("No Coverage report found for path {}", report);
      return;
    }

    // We parse the file
    ParserResult result = staxParser.parse(project, report);
    
    List<ProjectCoverage> projects = result.getProjects();
    List<FileCoverage> files = result.getSourceFiles();
   

    boolean excludeGeneratedCode = project.getConfiguration().getBoolean(
        SONAR_EXCLUDE_GEN_CODE_KEY, true);

    // Collect the files
    for (FileCoverage fileCoverage : files) {
      File sourcePath = fileCoverage.getFile();
      if (excludeGeneratedCode
          && GeneratedCodeFilter.INSTANCE.isGenerated(sourcePath.getName())) {
        // we will not include the generated code
        // in the sonar database
        log.info("Ignoring generated cs file " + sourcePath);
        continue;
      }
      collectFile(project, context, fileCoverage);
    }

    // Collect the projects
    int countLines = 0;
    int coveredLines = 0;

    for (ProjectCoverage projectCoverage : projects) {
      collectAssembly(project, context, projectCoverage);
      countLines += projectCoverage.getCountLines();
      coveredLines += projectCoverage.getCoveredLines();
    }

    // Computes the global coverage
    double coverage = Math.round(100. * coveredLines / countLines) * 0.01;
    context.saveMeasure(CoreMetrics.COVERAGE, convertPercentage(coverage));
    context.saveMeasure(CoverageMetrics.ELOC, (double) countLines);
    
    final Map<CSharpFolder, FolderCoverage> folderCoverageMap = new HashMap<CSharpFolder, FolderCoverage>();
    for (FileCoverage fileCoverage : files) {
      
      File directory = fileCoverage.getFile().getParentFile();
      CSharpFolder folderResource = CSharpFolder.fromDirectory(project,
          directory);
      FolderCoverage folderCoverage = folderCoverageMap.get(folderResource);
      if (folderCoverage == null) {
        folderCoverage = new FolderCoverage();
        folderCoverage.setFolderName(directory.getName());
        folderCoverageMap.put(folderResource, folderCoverage);
      }
      folderCoverage.addFile(fileCoverage);
    }
    Set<CSharpFolder> folders = folderCoverageMap.keySet();
    for (CSharpFolder cSharpFolder : folders) {
      FolderCoverage folderCoverage = folderCoverageMap.get(cSharpFolder);
      folderCoverage.summarize();
      
      saveCoverageMeasures(context, folderCoverage, cSharpFolder);
    }
    
  }

  /**
   * Collects the coverage at the assembly level
   * 
   * @param context
   * @param projectCoverage
   */
  private void collectAssembly(Project project, SensorContext context,
      ProjectCoverage projectCoverage) {
    double coverage = projectCoverage.getCoverage();
    String assemblyName = projectCoverage.getAssemblyName();
    VisualStudioSolution solution;
    try {
      solution = VisualUtils.getSolution(project);

      VisualStudioProject visualProject = solution.getProject(assemblyName);
      if (visualProject != null) {
        Project assemblyResource = VisualUtils.getProjectForVisualStudioProject(project, visualProject);
        context.saveMeasure(assemblyResource, CoreMetrics.COVERAGE,
            convertPercentage(coverage));
        context.saveMeasure(assemblyResource, CoverageMetrics.ELOC,
            (double) projectCoverage.getCountLines());
      }
    } catch (DotNetProjectException e) {
      log.debug("Could not find a .Net project : ", e);
    }
  }

  /**
   * Collects the results for a class
   * 
   * @param context
   * @param classCoverage
   */
  private void collectFile(Project project, SensorContext context,
      FileCoverage fileCoverage) {
    File filePath = fileCoverage.getFile();
    CSharpFile fileResource = CSharpFileLocator.INSTANCE.locate(project,
        filePath, false);
    if (fileResource != null) {
      saveCoverageMeasures(context, fileCoverage, fileResource);
      context.saveMeasure(fileResource, getHitData(fileCoverage));
    }
  }

  private void saveCoverageMeasures(SensorContext context,
      Coverable coverageData, Resource<?> resource) {
    double coverage = coverageData.getCoverage();
    // We have the effective number of lines here
    // TODO Is it really useful ?
    context.saveMeasure(resource, CoverageMetrics.ELOC,
        (double) coverageData.getCountLines());
    
    context.saveMeasure(resource, CoreMetrics.LINES_TO_COVER,
        (double) coverageData.getCountLines());
    
    context.saveMeasure(resource, CoreMetrics.UNCOVERED_LINES,
        (double) coverageData.getCountLines() - coverageData.getCoveredLines());

    context.saveMeasure(resource, CoreMetrics.COVERAGE,
        convertPercentage(coverage));
    // TODO LINE_COVERAGE & COVERAGE should not be the same 
    context.saveMeasure(resource, CoreMetrics.LINE_COVERAGE,
        convertPercentage(coverage));
  }

  /**
   * Generates a measure that contains the visits of each line of the source
   * file.
   * 
   * @param coverable
   *          the source file result
   * @return a measure to store
   */
  private Measure getHitData(FileCoverage coverable) {
    lineHitsBuilder.clear();
    Map<Integer, SourceLine> lines = coverable.getLines();
    for (SourceLine line : lines.values()) {
      int lineNumber = line.getLineNumber();
      int countVisits = line.getCountVisits();
      lineHitsBuilder.add(Integer.toString(lineNumber), countVisits);
    }
    Measure hitData = lineHitsBuilder.build().setPersistenceMode(
        PersistenceMode.DATABASE);
    return hitData;
  }

  /**
   * Converts a number to a percentage
   * 
   * @param percentage
   * @return
   */
  private double convertPercentage(Number percentage) {
    return ParsingUtils.scaleValue(percentage.doubleValue() * 100.0);
  }

  /**
   * Gets the plugin handle.
   * 
   * @param project
   *          he project to process.
   * @return the plugin handler for the project
   */
  @Override
  public MavenPluginHandler getMavenPluginHandler(Project project) {
    String mode = getCoverageMode(project);
    final MavenPluginHandler pluginHandlerReturned;
    if (COVERAGE_DEFAULT_MODE.equalsIgnoreCase(mode)) {
      pluginHandlerReturned = pluginHandler;
    } else {
      pluginHandlerReturned = null;
    }
    return pluginHandlerReturned;
  }

  @Override
  public boolean shouldExecuteOnProject(Project project) {
    String mode = getCoverageMode(project);
    return super.shouldExecuteOnProject(project)
        && !COVERAGE_SKIP_MODE.equalsIgnoreCase(mode);
  }

  private String getCoverageMode(Project project) {
    return project.getConfiguration().getString(COVERAGE_MODE_KEY, COVERAGE_DEFAULT_MODE);
  }
}
