/* Copyright 2016-2017 Esteve Fernandez <esteve@apache.org>
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
import org.gradle.api.plugins.JavaPlugin
import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.Delete
import org.gradle.api.tasks.compile.JavaCompile

import com.android.build.gradle.AppPlugin
import com.android.build.gradle.LibraryPlugin

class AmentPluginExtension {
  def project

  def buildSpace
  def installSpace
  def dependencies
  def packageManifestName
  def gradleRecursiveDependencies
  def execDependencyPathsInWorkspace

  def androidSTL
  def androidABI
  def androidNDK
  def androidVariant

  def checkProperty(String propertyName) {
    if (!project.hasProperty(propertyName)) {
      throw new MissingPropertyException("Missing Ament Gradle property: ${propertyName}")
    }
    return project.getProperty(propertyName)
  }

  AmentPluginExtension(Project project) {
    this.project = project
    this.buildSpace = checkProperty('ament.build_space')
    this.installSpace = checkProperty('ament.install_space')
    this.dependencies = checkProperty('ament.dependencies')
    this.packageManifestName = checkProperty('ament.package_manifest.name')
    this.gradleRecursiveDependencies = checkProperty('ament.gradle_recursive_dependencies').toBoolean()
    this.execDependencyPathsInWorkspace = checkProperty('ament.exec_dependency_paths_in_workspace')

    if(project.hasProperty('android')) {
      this.androidSTL = checkProperty('ament.android_stl')
      this.androidABI = checkProperty('ament.android_abi')
      this.androidNDK = checkProperty('ament.android_ndk')
      this.androidVariant = checkProperty('ament.android_variant')
    }
  }
}

class AmentPlugin implements Plugin<Project> {
  // This could be either an Android application (.apk) or a library (.aar)
  def getAndroidProjectType(Project project) {
      return project.plugins.hasPlugin('com.android.application')
          ? com.android.build.gradle.AppPlugin
          : com.android.build.gradle.LibraryPlugin;
  }

  def applyJava(Project project) {
    project.plugins.withType(org.gradle.api.plugins.JavaPlugin) {
      project.sourceSets {
        main {
          output.classesDir = project.file("${project.ament.buildSpace}/classes/main")
          output.resourcesDir = project.file("${project.ament.buildSpace}/resources/main")
        }
      }

      project.dependencies {
        project.ament.dependencies.split(':').each {
          compile project.fileTree(dir: [it, 'java'].join(File.separator),
            include: '*.jar')
        }
      }

      project.jar {
        destinationDir = project.file(
          [project.ament.installSpace, 'share',
            project.ament.packageManifestName,
            'java'].join(File.separator)
        )
      }
    }
  }

  def applyAndroid(Project project) {
    def installSpaceDir = null

    project.plugins.withType(com.android.build.gradle.AppPlugin) {
      installSpaceDir = project.file(project.ament.installSpace)

      project.android.applicationVariants.all { variant ->
        variant.outputs.each { output ->
          output.outputFile = new File(project.ament.buildSpace, output.outputFile.name)
        }
      }
    }

    project.plugins.withType(com.android.build.gradle.LibraryPlugin) {
      installSpaceDir = project.file(
        [project.ament.installSpace, 'share',
          project.ament.packageManifestName,
          'android'].join(File.separator))

      project.android.libraryVariants.all { variant ->
        variant.outputs.each { output ->
          output.outputFile = new File(
            [project.ament.buildSpace, 'share',
              project.ament.packageManifestName,
              'android'].join(File.separator),
            output.outputFile.name)
        }
      }
    }

    project.plugins.withType(getAndroidProjectType(project)) {
      project.android {
        project.buildDir = project.file(project.ament.buildSpace)
        sourceSets {
          main {
            jniLibs.srcDirs = [[project.buildDir, 'jniLibs'
              ].join(File.separator)]
          }
        }
      }

      def buildSpaceDir = project.file(project.ament.buildSpace)

      project.buildDir = buildSpaceDir

      project.task('deployArtifacts').doLast {
        project.copy {
          description = "Copy artifacts to the install space"

          from(buildSpaceDir) {
            include '**/*.apk'
            include '**/*.aar'
          }
          into installSpaceDir
          includeEmptyDirs = false
          eachFile {
            details ->
            details.path = details.file.name
          }
        }
      }

      def stlDestination = [buildSpaceDir, 'jniLibs', project.ament.androidABI
        ].join(File.separator)

      project.task('cleanNativeLibs', type: Delete) {
        delete stlDestination
      }

      project.task('copyNativeLibs', type: Copy) {
        project.ament.dependencies.split(':').each {
          def fp = [project.file(it).parentFile.parentFile, 'lib'
            ].join(File.separator)
          def ft = project.fileTree(dir: fp, include: '*.so')
          from ft
          into stlDestination
        }

        // TODO(esteve): expand this to support other STL libraries
        def stlLibraryPath
        switch (project.ament.androidSTL) {
          case 'gnustl_shared':
            stlLibraryPath = [
              project.ament.androidNDK, 'sources', 'cxx-stl', 'gnu-libstdc++', '4.9',
              'libs', project.ament.androidABI, 'lib' + project.ament.androidSTL + '.so'
            ].join(File.separator)
            break
          case 'c++_shared':
            stlLibraryPath = [
              project.ament.androidNDK, 'sources', 'cxx-stl', 'llvm-libc++',
              'libs', project.ament.androidABI, 'lib' + project.ament.androidSTL + '.so'
            ].join(File.separator)
            break
        }
        from stlLibraryPath
      }

      project.copyNativeLibs.dependsOn project.cleanNativeLibs

      project.tasks.withType(JavaCompile) {
        compileTask -> compileTask.dependsOn project.copyNativeLibs
      }

      project.assemble.finalizedBy project.deployArtifacts

      def excludeJars = null
      def packageDependencies = null
      if (project.ament.gradleRecursiveDependencies) {
        excludeJars = []
        packageDependencies = project.ament.dependencies.split(':') as Set
      } else {
        // NOTE(esteve): this is a hack so that there are no multiDex duplicate errors
        // when building Android projects that use AAR
        excludeJars = ['builtin_interfaces_messages.jar', 'rcljava.jar', 'rcl_interfaces_messages.jar']
        packageDependencies = project.ament.execDependencyPathsInWorkspace.split(':') as Set
      }

      project.dependencies {
        packageDependencies.each {
          def compileJars = project.fileTree(dir: [it, 'java'].join(File.separator),
            include: '*.jar',
            exclude: excludeJars)
          compile compileJars
        }

        packageDependencies.each {
          def dependencyInstallDir = [it, 'android'].join(File.separator)
          project.fileTree(dir: dependencyInstallDir,
            include: '*-' + project.ament.androidVariant + '.aar').each { aarFile ->
              project.repositories {
                flatDir {
                  dirs dependencyInstallDir
                }
              }

              def aarBaseFilename = aarFile.name.substring(0, aarFile.name.lastIndexOf('.'))

              project.dependencies {
                compile (name: aarBaseFilename, ext: 'aar')
              }
            }
        }
      }
    }
  }

  void apply(Project project) {
    project.extensions.create("ament", AmentPluginExtension, project)

    applyJava(project)

    // Since the Android plugin is not part of the standard Gradle
    // distribution, we need to check at runtime if it has been loaded, and if
    // not, disregard any Android-specific code
    if(project.hasProperty('android')) {
      applyAndroid(project)
    }
  }
}
