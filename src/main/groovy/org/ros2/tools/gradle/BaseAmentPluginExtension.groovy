/* Copyright 2017 Esteve Fernandez <esteve@apache.org>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.ros2.tools.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project

class BaseAmentPluginExtension {
  def project

  def sourceSpace
  def buildSpace
  def installSpace
  def dependencies
  def packageManifestName
  def gradleRecursiveDependencies
  def execDependencyPathsInWorkspace

  def workingDir

  def properties
  def propertiesFile

  BaseAmentPluginExtension(Project project) {
    this.project = project
    this.workingDir = System.getProperty('user.dir')
    this.properties = new Properties()
    this.sourceSpace = project.findProperty('ament.source_space')
    if (this.sourceSpace != null) {
      this.propertiesFile = project.file([this.sourceSpace, 'gradle.properties'].join(File.separator))
    } else {
      this.propertiesFile = project.file('gradle.properties')
    }
    if (this.propertiesFile.exists()) {
      this.properties.load(this.propertiesFile.newDataInputStream())
    }

    this.buildSpace = project.findProperty('ament.build_space')
    this.installSpace = project.findProperty('ament.install_space')
    this.dependencies = project.findProperty('ament.dependencies')
    this.packageManifestName = project.findProperty('ament.package_manifest.name')
    def gradleRecursiveDependenciesProperty = project.findProperty('ament.gradle_recursive_dependencies')
    if (gradleRecursiveDependenciesProperty != null) {
        this.gradleRecursiveDependencies = gradleRecursiveDependenciesProperty.toBoolean()
    }
    this.execDependencyPathsInWorkspace = project.findProperty('ament.exec_dependency_paths_in_workspace')
  }

  def createCheckAmentPropertiesTask() {
    return this.project.task('checkAmentProperties').doLast {
      this.check()
    }
  }

  def setProperties() {
    this.properties.setProperty('ament.build_space', this.buildSpace)
    this.properties.setProperty('ament.install_space', this.installSpace)
    this.properties.setProperty('ament.dependencies', this.dependencies)
    this.properties.setProperty('ament.package_manifest.name', this.packageManifestName)
    this.properties.setProperty('ament.gradle_recursive_dependencies', String.valueOf(this.gradleRecursiveDependencies))
    this.properties.setProperty('ament.exec_dependency_paths_in_workspace', this.execDependencyPathsInWorkspace)
  }

  def createStoreAmentPropertiesTask() {
    return this.project.task('storeAmentPropertiesTask').doLast {
      this.setProperties()
      this.properties.store(this.propertiesFile.newWriter(), null)
    }
  }

  def checkProperty(String propertyName) {
    if (!project.hasProperty(propertyName)) {
      throw new MissingPropertyException("Missing Ament Gradle property: ${propertyName}")
    }
    return project.getProperty(propertyName)
  }

  def check() {
    this.buildSpace = checkProperty('ament.build_space')
    this.installSpace = checkProperty('ament.install_space')
    this.dependencies = checkProperty('ament.dependencies')
    this.packageManifestName = checkProperty('ament.package_manifest.name')
    this.gradleRecursiveDependencies = checkProperty('ament.gradle_recursive_dependencies').toBoolean()
    this.execDependencyPathsInWorkspace = checkProperty('ament.exec_dependency_paths_in_workspace')
  }

  def isValid() {
    return this.buildSpace != null && this.installSpace != null && this.dependencies != null &&
      this.packageManifestName != null && this.gradleRecursiveDependencies != null &&
      this.execDependencyPathsInWorkspace != null
  }
}
