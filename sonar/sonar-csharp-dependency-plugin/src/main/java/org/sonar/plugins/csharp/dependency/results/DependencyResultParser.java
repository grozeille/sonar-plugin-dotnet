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

package org.sonar.plugins.csharp.dependency.results;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.xml.stream.XMLStreamException;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;
import org.codehaus.staxmate.SMInputFactory;
import org.codehaus.staxmate.in.SMEvent;
import org.codehaus.staxmate.in.SMHierarchicCursor;
import org.codehaus.staxmate.in.SMInputCursor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sonar.api.BatchExtension;
import org.sonar.api.batch.SensorContext;
import org.sonar.api.design.Dependency;
import org.sonar.api.resources.Library;
import org.sonar.api.resources.Project;
import org.sonar.api.resources.Resource;
import org.sonar.api.utils.SonarException;
import org.sonar.plugins.csharp.api.CSharpResourcesBridge;
import org.sonar.plugins.csharp.core.AbstractStaxParser;

public class DependencyResultParser extends AbstractStaxParser implements BatchExtension {

  private static final Logger LOG = LoggerFactory.getLogger(DependencyResultParser.class);

  private Project project;

  private CSharpResourcesBridge csharpResourcesBridge;

  private SensorContext context;

  private DependencyResultCache dependencyResultCache;
  
  public DependencyResultParser(CSharpResourcesBridge csharpResourcesBridge, DependencyResultCache dependencyResultCache) {
    super();
    this.csharpResourcesBridge = csharpResourcesBridge;
    this.dependencyResultCache = dependencyResultCache;
  }

  public Project getProject() {
    return project;
  }

  public void setProject(Project project) {
    this.project = project;
  }

  public SensorContext getContext() {
    return context;
  }

  public void setContext(SensorContext context) {
    this.context = context;
  }

  @Override
  public void parse(File file) {
    SMInputFactory inputFactory = initStax();
    FileInputStream fileInputStream = null;
    try {
      fileInputStream = new FileInputStream(file);
      SMHierarchicCursor cursor = inputFactory.rootElementCursor(new InputStreamReader(fileInputStream, getEncoding()));
      SMInputCursor assemblyCursor = cursor.advance().descendantElementCursor("Assembly");
      parseAssemblyBlocs(assemblyCursor);
      cursor.getStreamReader().closeCompletely();
    } catch (XMLStreamException e) {
      throw new SonarException("Error while reading Gendarme result file: " + file.getAbsolutePath(), e);
    } catch (FileNotFoundException e) {
      throw new SonarException("Cannot find DependencyParser result file: " + file.getAbsolutePath(), e);
    } finally {
      IOUtils.closeQuietly(fileInputStream);
    }
  }

  private void parseAssemblyBlocs(SMInputCursor cursor) throws XMLStreamException {

    // Cursor is on <Assembly>
    while (cursor.getNext() != null) {
      if (cursor.getCurrEvent().equals(SMEvent.START_ELEMENT)) {

        String assemblyName = cursor.getAttrValue("name");
        String assemblyVersion = cursor.getAttrValue("version");

        Resource<?> from = getResource(assemblyName, assemblyVersion);

        // ignore references from another project of the solution, because we will resolve that later
        if (from instanceof Project && !from.equals(this.project)) {
          from = null;
        }
        else {
          SMInputCursor childCursor = cursor.childElementCursor();
          while (childCursor.getNext() != null) {
            if (childCursor.getLocalName().equals("References")) {
              SMInputCursor referenceCursor = childCursor.childElementCursor();
              parseReferenceBlock(referenceCursor, from);
            }
            else if (childCursor.getLocalName().equals("TypeReferences")) {
              SMInputCursor typeReferenceCursor = childCursor.childElementCursor();
              parseTypeReferenceBlock(typeReferenceCursor, from);
            }
          }
        }
      }
    }
  }

  private void parseReferenceBlock(SMInputCursor cursor, Resource<?> from) throws XMLStreamException {
    // Cursor is on <Reference>
    while (cursor.getNext() != null) {
      if (cursor.getCurrEvent().equals(SMEvent.START_ELEMENT)) {
        String referenceName = cursor.getAttrValue("name");
        String referenceVersion = cursor.getAttrValue("version");

        Resource<?> to = getResource(referenceName, referenceVersion);

        // keep the dependency in cache
        String key = from.getKey() + "\n" + to.getKey();
        Dependency dependency = new Dependency(from, to);
        dependency.setUsage("compile");
        dependency.setWeight(1);
        dependencyResultCache.getProjectDependencyMap().put(key, dependency);
        context.saveDependency(dependency);
        
        LOG.info("Saving dependency from " + from.getName() + " to " + to.getName());
      }
    }
  }

  private void parseTypeReferenceBlock(SMInputCursor cursor, Resource<?> from) throws XMLStreamException {
    // Cursor is on <From>
    while (cursor.getNext() != null) {
      if (cursor.getCurrEvent().equals(SMEvent.START_ELEMENT)) {
        String fromType = cursor.getAttrValue("fullname");

        SMInputCursor toCursor = cursor.childElementCursor();
        while (toCursor.getNext() != null) {
          if (toCursor.getCurrEvent().equals(SMEvent.START_ELEMENT)) {
            String toType = toCursor.getAttrValue("fullname");

            Resource<?> fromResource = csharpResourcesBridge.getFromTypeName(fromType);

            if (fromResource != null) {

              // save the dependency in cache
              DependencyItemResult itemResult = dependencyResultCache.getTypeDependencyMap().get(fromType);
              if (itemResult == null) {
                itemResult = new DependencyItemResult();
                dependencyResultCache.getTypeDependencyMap().put(fromType, itemResult);
              }

              itemResult.setFrom(fromResource);
              itemResult.getToList().add(toType);
            }
          }
        }
      }
    }
  }

  private Resource<?> getProjectFromKey(Project parentProject, String projectKey) {
    for (Project subProject : parentProject.getModules()) {
      if (subProject.getKey().equals(projectKey)) {
        return subProject;
      }
    }

    return null;
  }

  private Resource<?> getResource(String name, String version) {
    // try to find the project
    String projectKey = StringUtils.substringBefore(project.getParent().getKey(), ":") + ":" + StringUtils.deleteWhitespace(name);
    Resource<?> result = getProjectFromKey(project.getParent(), projectKey);

    // if not found in the solution, get the binary
    if (result == null) {

      Library library = new Library(name, version);
      library.setName(name + " " + version);
      result = context.getResource(library);

      // not already exists, save it
      if (result == null) {
        context.index(library);
      }
      result = library;

    }

    return result;
  }

  public void commitTypeDependencies() {
    Map<String, Dependency> folderDependencyMap = new HashMap<String, Dependency>();
    List<Dependency> fileDependencyList = new ArrayList<Dependency>();

    for (DependencyItemResult item : dependencyResultCache.getTypeDependencyMap().values()) {
      for (String toType : item.getToList()) {
        Resource<?> toResource = csharpResourcesBridge.getFromTypeName(toType);

        if (toResource != null) {
          
          // get the parent folder
          Resource<?> parentFolderFrom = (Resource<?>) item.getFrom().getParent();
          Resource<?> parentFolderTo = (Resource<?>) toResource.getParent();

          // get the parent project
          Resource<?> parentProjectFrom = (Resource<?>) parentFolderFrom.getParent();
          Resource<?> parentProjectTo = (Resource<?>) parentFolderTo.getParent();

          // find the project to project dependency
          Dependency projectDependency = null;
          if (parentFolderFrom != parentFolderTo) {
            String parentProjectKey = parentProjectFrom.getKey() + "\n" + parentProjectTo.getKey();
            projectDependency = dependencyResultCache.getProjectDependencyMap().get(parentProjectKey);
            if (projectDependency == null) {
              // should not happend!
              LOG.warn("Unknown projects: " + parentProjectFrom.getKey() + " and " + parentProjectTo.getKey());
            }
            projectDependency.setWeight(projectDependency.getWeight() + 1);
          }

          // create the folder to folder dependency
          Dependency folderDependency = null;
          if (parentFolderFrom != parentFolderTo) {
            
            String parentFolderKey = parentFolderFrom.getKey() + "\n" + parentFolderTo.getKey();
            folderDependency = folderDependencyMap.get(parentFolderKey);
            if (folderDependency == null) {
              folderDependency = new Dependency(parentFolderFrom, parentFolderTo);
              folderDependency.setUsage("USES");
              folderDependency.setParent(projectDependency);
              folderDependency.setWeight(1);
              folderDependencyMap.put(parentFolderKey, folderDependency);
            }
            else {
              folderDependency.setWeight(folderDependency.getWeight() + 1);
            }            
          }
          
          // create the file to file dependency
          Dependency fileDependency = new Dependency(item.getFrom(), toResource);
          fileDependency.setUsage("USES");
          fileDependency.setParent(folderDependency);
          fileDependency.setWeight(1); // TODO : the XML contains only 1 reference, change it to contains the numbers
          
          fileDependencyList.add(fileDependency);
        }
      }
    }
    
    // save all
    for(Dependency projectDependency : dependencyResultCache.getProjectDependencyMap().values()){
      context.saveDependency(projectDependency);

      LOG.info("Updating dependency from " + projectDependency.getFrom().getName() + " to " + projectDependency.getTo().getName());
    }
    
    for(Dependency folderDependency : folderDependencyMap.values()){
      context.saveDependency(folderDependency);

      LOG.info("Saving dependency from " + folderDependency.getFrom().getName() + " to " + folderDependency.getTo().getName());
    }
    
    for(Dependency fileDependency : fileDependencyList){
      context.saveDependency(fileDependency);

      LOG.info("Saving dependency from " + fileDependency.getFrom().getName() + " to " + fileDependency.getTo().getName());
    }
  }
}
