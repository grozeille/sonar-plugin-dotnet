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
 * Created on Sep 1, 2009
 */
package org.sonar.plugin.dotnet.core.resource;

import java.io.File;
import java.util.Date;

import org.apache.commons.lang.StringUtils;
import org.apache.maven.dotnet.commons.project.DotNetProjectException;
import org.apache.maven.dotnet.commons.project.VisualStudioProject;
import org.apache.maven.dotnet.commons.project.VisualStudioSolution;
import org.sonar.api.resources.Project;
import org.sonar.api.utils.WildcardPattern;
import org.sonar.plugin.dotnet.core.CSharp;
import org.sonar.plugin.dotnet.core.project.VisualUtils;

/**
 * An assembly resource, corresponding to a visual studio project.
 * 
 * @author Jose CHILLAN Sep 1, 2009
 */
public class CLRAssembly extends Project {
  private final VisualStudioProject visualProject;

  /**
   * Gets an assembly from its name.
   * 
   * @param project
   *          the solution project that should contains the assembly
   * @param assemblyName
   *          the name of the assembly
   * @return a new assembly, or <code>null</code> if the assembly was not found
   */
  public static CLRAssembly fromName(Project project, String assemblyName) {
    try {
      VisualStudioSolution solution = VisualUtils.getSolution(project);

      VisualStudioProject visualProject = solution.getProject(assemblyName);
      if (visualProject != null) {
        CLRAssembly assemblyResource = forVisualStudioProject(project, visualProject);
        return assemblyResource;
      }
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
  public static CLRAssembly forFile(Project project, File file) {
    try {
      VisualStudioSolution solution = VisualUtils.getSolution(project);
      VisualStudioProject visualProject = solution.getProjectByLocation(file);
      if (visualProject != null) {
        return forVisualStudioProject(project, visualProject);
      }
    } catch (Exception e) {
      // Nothing special
    }
    return null;
  }
  
  public static CLRAssembly forVisualStudioProject(Project parent, VisualStudioProject project){
	String key = parent.getKey()+":"+ CSharp.createKey(project.getAssemblyName(), null, null);
	
	CLRAssembly clrAssembly = null;
	
  	for(Project p : parent.getModules()){
  		if(p.getKey().equals(key)){
  			clrAssembly = (CLRAssembly)p;
  			break;
  		}
  	}
  	
  	if(clrAssembly == null){
  		clrAssembly = new CLRAssembly(parent, project);  		
  	}
  	
  	return clrAssembly;
  	
  }

  /**
   * Constructs the assembly from the project.
   * 
   * @param project
   *          the visual project link
   */
  protected CLRAssembly(Project parent, VisualStudioProject project) {	
	super("");
	String assemblyName = project.getAssemblyName();
    //super();
	String key = parent.getKey()+":"+ CSharp.createKey(assemblyName, null, null);
	setKey(key);
    this.visualProject = project;
    setName("Project " + project.getAssemblyName());
    setParent(parent);
    setAnalysisDate(parent.getAnalysisDate());
    setAnalysisType(parent.getAnalysisType());
    setConfiguration(parent.getConfiguration());
    setAnalysisVersion(project.getAssemblyVersion());
    setLanguage(CSharp.INSTANCE);
    setLanguageKey(CSharp.KEY);
    setLatestAnalysis(true);
    setPackaging(parent.getPackaging());
  }

  /**
   * Gets the name of the .Net assembly.
   * 
   * @return the assembly name
   */
  public String getAssemblyName() {
    return visualProject.getAssemblyName();
  }

  /**
   * @return
   */
  @Override
  public String getLongName() {
    return "Assembly " + getAssemblyName();
  }

  /**
   * @param antPattern
   * @return
   */
  @Override
  public boolean matchFilePattern(String antPattern) {
    String patternWithoutFileSuffix = StringUtils.substringBeforeLast(
        antPattern, ".");
    WildcardPattern matcher = WildcardPattern.create(patternWithoutFileSuffix,
        ".");
    return matcher.match(getKey());
  }

  /**
   * Returns the visualProject.
   * 
   * @return The visualProject to return.
   */
  public VisualStudioProject getVisualProject() {
    return this.visualProject;
  }

}
