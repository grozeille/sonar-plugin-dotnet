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

package org.sonar.plugins.csharp.dependency;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.InputStreamReader;

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
import org.sonar.plugins.csharp.core.AbstractStaxParser;

public class DependencyParserResultParser extends AbstractStaxParser implements BatchExtension {

  private static final Logger LOG = LoggerFactory.getLogger(DependencyParserResultParser.class);

  private Project project;

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

  private SensorContext context;

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
          SMInputCursor referencesCursor = cursor.childElementCursor();
          if(referencesCursor.getNext() != null) {
            SMInputCursor referenceCursor = referencesCursor.childElementCursor();
            parseReferenceBlock(referenceCursor, from);
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

        // save the dependency
        Dependency dependency = new Dependency(from, to);
        dependency.setUsage("compile");
        dependency.setWeight(1);
        context.saveDependency(dependency);

        LOG.info("Saving dependency from " + from.getName() + " to " + to.getName());
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
}
