/*
 * Sonar C# Plugin :: Core
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

package org.sonar.plugins.csharp.api.sensor;

import java.util.List;

import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sonar.api.batch.Sensor;
import org.sonar.api.batch.SensorContext;
import org.sonar.api.design.Dependency;
import org.sonar.api.resources.Project;
import org.sonar.api.resources.Resource;
import org.sonar.dotnet.tools.commons.visualstudio.ProjectReference;
import org.sonar.dotnet.tools.commons.visualstudio.VisualStudioProject;
import org.sonar.dotnet.tools.commons.visualstudio.VisualStudioSolution;
import org.sonar.plugins.csharp.api.CSharp;
import org.sonar.plugins.csharp.api.MicrosoftWindowsEnvironment;

public class ProjectDependenciesSensor implements Sensor {

	private final static Logger log = LoggerFactory.getLogger(ProjectDependenciesSensor.class);
	
	private CSharp cSharp;
	
	private MicrosoftWindowsEnvironment microsoftWindowsEnvironment;

	public ProjectDependenciesSensor(CSharp cSharp, MicrosoftWindowsEnvironment microsoftWindowsEnvironment) {
		this.cSharp = cSharp;
		this.microsoftWindowsEnvironment = microsoftWindowsEnvironment;
	}

	public void analyse(Project project, SensorContext context) {
			
			VisualStudioSolution solution = microsoftWindowsEnvironment.getCurrentSolution();
			List<VisualStudioProject> projects = solution.getProjects();
			
			// resolve dependencies for each projects of the solution
			for (VisualStudioProject vsProject : projects) {

				// find the referenced project in the modules
				String projectKey = StringUtils.substringBefore(project.getKey(), ":") + ":" + StringUtils.deleteWhitespace(vsProject.getName());
				Resource<?> subProject = getProjectFromKey(project, projectKey);

				// resolve project references
				List<ProjectReference> projectReferences = vsProject.getProjectReferences();
				for (ProjectReference projectReference : projectReferences) {

					// find the referenced project in the solution
					VisualStudioProject vsReferencedProject = solution.getProject(projectReference.getGuid());

					// the project should already exists
					String referenceKey = StringUtils.substringBefore(project.getKey(), ":") + ":" + StringUtils.deleteWhitespace(vsReferencedProject.getName());
					Resource<?> referencedProject =  getProjectFromKey(project, referenceKey);

					// save the dependency
					Dependency dependency = new Dependency(subProject, referencedProject);
					dependency.setUsage("compile");
					dependency.setWeight(1);
					context.saveDependency(dependency);

					log.info("Saving dependency from " + subProject.getName() + " to " + referencedProject.getName());

					/*
					 * String json = "[{\"i\":"+referencedProject.getId()+
					 * ",\"n\":\""+referencedProject.getName()+
					 * "\",\"q\":\""+referencedProject.getQualifier()+"\",\"v\":[{}]";
					 * 
					 * Measure measure = new Measure(CoreMetrics.DEPENDENCY_MATRIX, json);
					 * measure.setPersistenceMode(PersistenceMode.DATABASE);
					 * context.saveMeasure(subProject, measure);
					 * log.info("Saving measure"+measure.toString());
					 */

					// TODO: do it also for PRJ > Folder/Package > File dependencies
				}

			}
	}

	public boolean shouldExecuteOnProject(Project project) {
    return cSharp.equals(project.getLanguage()) && project.isRoot();
  }
	
	private Resource<?> getProjectFromKey(Project parentProject, String projectKey){
		for(Project subProject : parentProject.getModules()){
			if(subProject.getKey().equals(projectKey)){
				return subProject;
			}
		}
		
		return null;
	}
	
}
