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
package org.sonar.plugin.dotnet.core;

import java.util.List;

import org.apache.maven.dotnet.commons.project.BinaryReference;
import org.apache.maven.dotnet.commons.project.DotNetProjectException;
import org.apache.maven.dotnet.commons.project.ProjectReference;
import org.apache.maven.dotnet.commons.project.VisualStudioProject;
import org.apache.maven.dotnet.commons.project.VisualStudioSolution;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sonar.api.batch.Sensor;
import org.sonar.api.batch.SensorContext;
import org.sonar.api.batch.SonarIndex;
import org.sonar.api.design.Dependency;
import org.sonar.api.measures.CoreMetrics;
import org.sonar.api.measures.Measure;
import org.sonar.api.measures.PersistenceMode;
import org.sonar.api.resources.Library;
import org.sonar.api.resources.Project;
import org.sonar.api.resources.Resource;
import org.sonar.plugin.dotnet.core.project.VisualUtils;
import org.sonar.plugin.dotnet.core.resource.CLRAssembly;

public class ProjectDependenciesSensor implements Sensor {

	private final static Logger log = LoggerFactory
			.getLogger(BinaryDependenciesSensor.class);

	private final SonarIndex index;

	public ProjectDependenciesSensor(SonarIndex index) {
		this.index = index;
	}

	@Override
	public void analyse(Project project, SensorContext context) {

		try {
			VisualStudioSolution solution = VisualUtils.getSolution(project);
			List<VisualStudioProject> projects = solution.getProjects();

			// resolve dependencies for each projects of the solution
			for (VisualStudioProject vsProject : projects) {

				// find the referenced project in the Maven modules
				Resource<?> subProject = CLRAssembly.fromName(project,
						vsProject.getAssemblyName());
				
				Resource<?> savedSubPrj = context
						.getResource(subProject);
				if (savedSubPrj == null) {
					context.saveResource(subProject);
					savedSubPrj = context.getResource(subProject);
				}
				subProject = savedSubPrj;

				// resolve project references
				List<ProjectReference> projectReferences = vsProject
						.getProjectReferences();
				for (ProjectReference projectReference : projectReferences) {

					// find the referenced project in the solution
					VisualStudioProject vsReferencedProject = solution
							.getProject(projectReference.getGuid());

					Resource<?> referencedProject = CLRAssembly.fromName(
							project, vsReferencedProject.getAssemblyName());

					Resource<?> savedReferencedPrj = context
							.getResource(referencedProject);
					if (savedReferencedPrj == null) {
						log.info("Saving resource"+savedReferencedPrj.toString());
						context.saveResource(referencedProject);
						savedReferencedPrj = context.getResource(referencedProject);
					}
					referencedProject = savedReferencedPrj;
					
					Dependency dependency = new Dependency(subProject, referencedProject);
					dependency.setUsage("compile");
					dependency.setWeight(1);
					context.saveDependency(dependency);
					log.info("Saving dependency"+dependency.toString());
					
/*
					String json = "[{\"i\":"+referencedProject.getId()+
						",\"n\":\""+referencedProject.getName()+
						"\",\"q\":\""+referencedProject.getQualifier()+"\",\"v\":[{}]";
					
					Measure measure = new Measure(CoreMetrics.DEPENDENCY_MATRIX, json);
				    measure.setPersistenceMode(PersistenceMode.DATABASE);
				    context.saveMeasure(subProject, measure);
				    log.info("Saving measure"+measure.toString());*/
				    
				    // TODO: do it also for PRJ > Folder/Package > File dependencies
				}

			}

		} catch (DotNetProjectException e) {
			log.error("Error during binary dependency analysis", e);
		}
	}

	@Override
	public boolean shouldExecuteOnProject(Project project) {
		String packaging = project.getPackaging();
		// We only accept the "sln" packaging
		return "sln".equals(packaging);
	}

}
