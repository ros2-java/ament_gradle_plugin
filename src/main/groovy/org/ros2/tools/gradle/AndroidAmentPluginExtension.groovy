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
import org.gradle.api.artifacts.DependencyResolutionListener
import org.gradle.api.artifacts.ResolvableDependencies
import org.gradle.api.tasks.compile.AbstractCompile

class AndroidAmentPluginExtension extends BaseAmentPluginExtension {
  def androidSTL
  def androidABI
  def androidNDK
  def androidVariant

  def stlDestination

  AndroidAmentPluginExtension(project) {
    super(project)
    this.androidSTL = project.findProperty('ament.android_stl')
    this.androidABI = project.findProperty('ament.android_abi')
    this.androidNDK = project.findProperty('ament.android_ndk')
    this.androidVariant = project.findProperty('ament.android_variant')
    this.stlDestination = [this.workingDir, 'src', 'main', 'jniLibs', this.androidABI
      ].join(File.separator)
  }

  def getAndroidProjectType() {
      return project.plugins.hasPlugin('com.android.application')
          ? com.android.build.gradle.AppPlugin
          : com.android.build.gradle.LibraryPlugin;
  }

  def check() {
    super.check()
    this.androidSTL = checkProperty('ament.android_stl')
    this.androidABI = checkProperty('ament.android_abi')
    this.androidNDK = checkProperty('ament.android_ndk')
    this.androidVariant = checkProperty('ament.android_variant')
  }

  def setProperties() {
    super.setProperties()
    this.properties.setProperty('ament.android_stl', this.androidSTL)
    this.properties.setProperty('ament.android_abi', this.androidABI)
    this.properties.setProperty('ament.android_ndk', this.androidNDK)
    this.properties.setProperty('ament.android_variant', this.androidVariant)
  }

  def isValid() {
    return super.isValid() && this.androidSTL != null && this.androidABI != null &&
      this.androidNDK != null && this.androidVariant != null
  }

  def updateAndroidDependencies() {
    if (!this.isValid()) {
      return
    }

    def excludeJars = null
    def packageDependencies = null
    if (this.gradleRecursiveDependencies) {
      excludeJars = []
      packageDependencies = this.dependencies.split(':') as Set
    } else {
      // NOTE(esteve): this is a hack so that there are no multiDex duplicate errors
      // when building Android projects that use AAR
      excludeJars = ['builtin_interfaces_messages.jar', 'rcl_interfaces_messages.jar', 'rcljava.jar']
      packageDependencies = this.execDependencyPathsInWorkspace.split(':') as Set
    }

    def compileDeps = project.getConfigurations().getByName("implementation").getDependencies()
    def fileDeps = project.files(packageDependencies.collect {
      project.fileTree(dir: [it, 'java'].join(File.separator),
          include: '*.jar',
          exclude: excludeJars)
    })
    compileDeps.add(project.getDependencies().create(fileDeps))

    packageDependencies.each {
      def dependencyInstallDir = [it, 'android'].join(File.separator)
      def aarFiles = project.fileTree(dir: dependencyInstallDir,
        include: '*-' + this.androidVariant + '.aar')
      aarFiles.each { aarFile ->
        project.repositories {
          flatDir {
            dirs dependencyInstallDir
          }
        }

        def aarBaseFilename = aarFile.name.substring(0, aarFile.name.lastIndexOf('.'))

        compileDeps.add(project.getDependencies().create([name: aarBaseFilename, ext: 'aar']))
      }
    }
  }

  def createDeployArtifactsTask() {
    return project.task('deployArtifacts').doLast {
      if(!this.isValid()) {
        return
      }

      def installSpaceDir = null

      project.plugins.withType(com.android.build.gradle.AppPlugin) {
        installSpaceDir = project.file(this.installSpace)
      }

      project.plugins.withType(com.android.build.gradle.LibraryPlugin) {
        installSpaceDir = project.file(
          [this.installSpace, 'share',
            this.packageManifestName,
            'android'].join(File.separator))
      }

      project.copy {
        description = 'Copy artifacts to the install space'
        from(this.workingDir) {
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
  }

    def createCleanNativeLibsTask() {
      return project.task('cleanNativeLibs').doLast {
        if(!this.isValid()) {
          return
        }

        project.delete(this.stlDestination)
     }
   }

   def createCopyNativeLibsTask() {
      return project.task('copyNativeLibs').doLast {
        if(!this.isValid()) {
          return
        }

      project.copy {
        project.ament.dependencies.split(':').each {
          def fp = [project.file(it).parentFile.parentFile, 'lib'
              ].join(File.separator)
          def ft = project.fileTree(
            dir: fp,
            include: ['*.so'])
          ft += project.fileTree(
            dir: [fp, 'jni'].join(File.separator),
            include: ['*.so'])
          from ft
          into this.stlDestination
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
     }
   }

  def setup() {
    project.plugins.withType(getAndroidProjectType()) {
      project.getGradle().addListener(new DependencyResolutionListener() {
          @Override
          void beforeResolve(ResolvableDependencies resolvableDependencies) {
            updateAndroidDependencies()
            project.getGradle().removeListener(this)
          }

          @Override
          void afterResolve(ResolvableDependencies resolvableDependencies) {}
      })

      def checkAmentPropertiesTask = createCheckAmentPropertiesTask()

      def storeAmentPropertiesTask = createStoreAmentPropertiesTask()
      storeAmentPropertiesTask.dependsOn checkAmentPropertiesTask

      def cleanNativeLibsTask = createCleanNativeLibsTask()
      cleanNativeLibsTask.dependsOn storeAmentPropertiesTask

      def copyNativeLibsTask = createCopyNativeLibsTask()
      copyNativeLibsTask.dependsOn cleanNativeLibsTask

      project.tasks.withType(AbstractCompile) {
        compileTask -> compileTask.dependsOn copyNativeLibsTask
      }

      def deployArtifactsTask = createDeployArtifactsTask()
      project.assemble.finalizedBy deployArtifactsTask
    }
  }
}
