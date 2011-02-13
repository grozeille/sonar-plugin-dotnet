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
 *
 */
package org.sonar.plugin.dotnet.core.project;

import java.io.File;
import java.io.FilenameFilter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.sourceforge.pmd.cpd.CsLanguage;

import org.apache.commons.io.filefilter.IOFileFilter;
import org.apache.maven.dotnet.commons.project.DotNetProjectException;
import org.apache.maven.dotnet.commons.project.SourceFile;
import org.apache.maven.dotnet.commons.project.VisualStudioProject;
import org.apache.maven.dotnet.commons.project.VisualStudioSolution;
import org.apache.maven.dotnet.commons.project.VisualStudioUtils;
import org.apache.maven.project.MavenProject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sonar.api.resources.DefaultProjectFileSystem;
import org.sonar.api.resources.Project;
import org.sonar.api.utils.SonarException;
import org.sonar.api.utils.WildcardPattern;
import org.sonar.plugin.dotnet.core.CSharp;

/**
 * Utility classes for Visual Studio projects associated to Maven projects.
 * 
 * @author Jose CHILLAN May 14, 2009
 */
public final class VisualUtils {

  private final static Logger log = LoggerFactory.getLogger(VisualUtils.class);

  // maybe way too complicated since one thread and one solution only during the
  // analysis
  private final static Map<MavenProject, VisualStudioSolution> solutionCache = Collections
      .synchronizedMap(new HashMap<MavenProject, VisualStudioSolution>());
  
  private final static Map<VisualStudioProject, Project> projectCache = Collections
  		.synchronizedMap(new HashMap<VisualStudioProject, Project>());

  /**
   * Extracts a visual studio solution if the project is a valid solution.
   * 
   * @param project
   *          the maven project from which a solution will be extracted
   * @return a visual studio solution
   * @throws DotNetProjectException
   *           if the project is not a valid .Net project
   */
  public static VisualStudioSolution getSolution(Project project)
      throws DotNetProjectException {
    MavenProject mavenProject = project.getPom();
    final VisualStudioSolution solution;
    if (solutionCache.containsKey(mavenProject)) {
      solution = solutionCache.get(mavenProject);
    } else {
      solution = VisualStudioUtils.getVisualSolution(mavenProject,
          (String) null);
      solutionCache.put(mavenProject, solution);
    }
    return solution;
  }
  
  /**
   * Gets an assembly from its name.
   * 
   * @param project
   *          the solution project that should contains the assembly
   * @param assemblyName
   *          the name of the assembly
   * @return a new assembly, or <code>null</code> if the assembly was not found
   */
  public static Project getProjectFromName(Project project, String assemblyName) {
    try {
      VisualStudioSolution solution = VisualUtils.getSolution(project);

      VisualStudioProject visualProject = solution.getProject(assemblyName);
      if (visualProject != null) {
    	  Project assemblyResource = getProjectForVisualStudioProject(project, visualProject);
        return assemblyResource;
      }
    } catch (DotNetProjectException e) {
      // Do nothing
    }
    return null;
  }
  
  public static VisualStudioProject getVisualStudioProject(Project project){
	  try {
	      VisualStudioSolution solution = VisualUtils.getSolution(project.getParent());

	      // extract the assemblyName from the key
	      String assemblyName = project.getKey().substring(project.getParent().getKey().length()+1);
	      
	      VisualStudioProject visualProject = solution.getProject(assemblyName);

	      return visualProject;
	    } catch (DotNetProjectException e) {
	      // Do nothing
	    }
	    return null;
  }
  
  /**
   * Gets the assembly for a given file.
   * 
   * @param project
   * @param file
   * @return
   */
  public static Project getProjectForFile(Project project, File file) {
    try {
      VisualStudioSolution solution = VisualUtils.getSolution(project);
      VisualStudioProject visualProject = solution.getProjectByLocation(file);
      if (visualProject != null) {
        return getProjectForVisualStudioProject(project, visualProject);
      }
    } catch (Exception e) {
      // Nothing special
    }
    return null;
  }
  
  public static Project getProjectForVisualStudioProject(Project parent, VisualStudioProject visualStudioProject){
	String assemblyName = visualStudioProject.getAssemblyName();
	  
	String key = parent.getKey()+":"+ CSharp.createKey(assemblyName, null, null);
	
	Project project = null;
	
  	for(Project p : parent.getModules()){
  		if(p.getKey().equals(key)){
  			project = (Project)p;
  			break;
  		}
  	}
  	
  	if(project == null){
  		project = new Project(key);
  		  	    
  		project.setName("Project " + assemblyName);
  		project.setParent(parent);
  		project.setAnalysisDate(parent.getAnalysisDate());
  		project.setAnalysisType(parent.getAnalysisType());
  		project.setConfiguration(parent.getConfiguration());
  	    project.setAnalysisVersion(visualStudioProject.getAssemblyVersion());
  	    project.setLanguage(CSharp.INSTANCE);
  	    project.setLanguageKey(CSharp.KEY);
  	    project.setLatestAnalysis(true);
  	    project.setPackaging(parent.getPackaging());
  	}
  	
  	return project;
  	
  }

  /**
   * Search all cs files (with corresponding VS project) included in the VS
   * solution corresponding to the sonar project object argument.
   * 
   * @param project
   * @return
   * @throws DotNetProjectException
   */
  public static Map<File, VisualStudioProject> buildCsFileProjectMap(
      Project project) {

    VisualStudioSolution solution;
    try {
      solution = VisualUtils.getSolution(project);
    } catch (DotNetProjectException e) {
      throw new SonarException(e);
    }
    List<VisualStudioProject> projects = solution.getProjects();
    FilenameFilter generatedCodeFilter = new CsLanguage().getFileFilter();

    Map<File, VisualStudioProject> csFiles = new HashMap<File, VisualStudioProject>();

    String[] excludedProjectNames 
      = project.getConfiguration().getStringArray("sonar.skippedModules");
    Set<String> excludedProjectNameSet = new HashSet<String>();
    if (excludedProjectNames!=null) {
      excludedProjectNameSet.addAll(Arrays.asList(excludedProjectNames));
    }
    
    WildcardPattern[] patterns 
      = WildcardPattern.create(project.getExclusionPatterns());
    
    for (VisualStudioProject visualStudioProject : projects) {
      
      if (excludedProjectNameSet.contains(visualStudioProject.getName())) {
        log.info("Project {} excluded, C# files of this project will be ignored", visualStudioProject.getName());
        continue;      
      }
      
      Collection<SourceFile> sources = visualStudioProject.getSourceFiles();
      
      ExclusionFilter exclusionFilter 
        = new ExclusionFilter(visualStudioProject.getDirectory(), patterns);
      
      for (SourceFile sourceFile : sources) {
        if (generatedCodeFilter.accept(sourceFile.getFile().getParentFile(), sourceFile.getName())
            && exclusionFilter.accept(sourceFile.getFile())) {
          csFiles.put(sourceFile.getFile(), visualStudioProject);
        }
      }
    }
    return csFiles;
  }

  public static List<File> buildCsFileList(Project project) {
    return new ArrayList<File>(buildCsFileProjectMap(project).keySet());
  }
  
  /**
   * Copy/Pasted from DefaultProjectFileSystem
   *
   */
  private static class ExclusionFilter implements IOFileFilter {
    File sourceDir;
    WildcardPattern[] patterns;

    ExclusionFilter(File sourceDir, WildcardPattern[] patterns) {
      this.sourceDir = sourceDir;
      this.patterns = patterns;
    }

    public boolean accept(File file) {
      String relativePath = DefaultProjectFileSystem.getRelativePath(file, sourceDir);
      if (relativePath == null) {
        return false;
      }
      for (WildcardPattern pattern : patterns) {
        if (pattern.match(relativePath)) {
          return false;
        }
      }
      return true;
    }

    public boolean accept(File file, String name) {
      return accept(file);
    }
  }

}
