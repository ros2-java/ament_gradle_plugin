/* Copyright 2016 Esteve Fernandez <esteve@apache.org>
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

class AmentPlugin implements Plugin<Project> {
  void apply(Project project) {

    project.plugins.withType(org.gradle.api.plugins.JavaPlugin) {
      project.sourceSets {
        main {
          if (project.hasProperty('ament.build_space')) {
            output.classesDir = project.file(
              project.getProperty('ament.build_space') + '/classes/main')
            output.resourcesDir = project.file(
              project.getProperty('ament.build_space') + '/resources/main')
          }
        }
      }

      project.dependencies {
        if (project.hasProperty('ament.dependencies')) {
          project.getProperty('ament.dependencies').split(':').each {
            compile project.fileTree(dir: [it, 'java'].join(File.separator),
              include: '*.jar')
          }
        }
      }

      project.jar {
        if (project.hasProperty('ament.install_space')) {
          destinationDir = project.file(
            [project.getProperty('ament.install_space'), 'share',
              'rcljava_examples', 'java'].join(File.separator)
          )
        }
      }
    }

    // Since the Android plugin is not part of the standard Gradle
    // distribution, we need to check at runtime if it has been loaded, and if
    // not, disregard any Android-specific code
    def androidAppPluginClass = null
    try {
      androidAppPluginClass = Class.forName(
        'com.android.build.gradle.AppPlugin')
    } catch(ClassNotFoundException) { }

    if (androidAppPluginClass != null) {

      project.plugins.withType(com.android.build.gradle.AppPlugin) {
        project.android {
          compileSdkVersion 22
          buildToolsVersion "23.0.3"
          if (project.hasProperty('ament.build_space')) {
            project.buildDir = project.file(
              project.getProperty('ament.build_space'))
            sourceSets {
              main {
                jniLibs.srcDirs = [[project.buildDir, 'jniLibs'
                  ].join(File.separator)]
              }
            }
          }
        }

        if (project.hasProperty('ament.build_space') &&
            project.hasProperty('ament.install_space')) {
          def installSpaceDir = project.file(
            project.getProperty('ament.install_space'))
          def buildSpaceDir = project.file(
            project.getProperty('ament.build_space'))

          project.buildDir = buildSpaceDir

          project.task('deployArtifacts') << {
            project.copy {
              description = "Copy artifacts to the install space"

              from("$buildSpaceDir") {
                include '**/*.apk'
              }
              into "$installSpaceDir"
              includeEmptyDirs = false
              eachFile {
                details ->
                details.path = details.file.name
              }
            }
          }

          if (project.hasProperty('ament.android_stl') &&
              project.hasProperty('ament.android_abi') &&
              project.hasProperty('ament.android_ndk')) {
            def androidSTL = project.getProperty('ament.android_stl')
            def androidABI = project.getProperty('ament.android_abi')
            def androidNDK = project.getProperty('ament.android_ndk')

            def stlDestination = [buildSpaceDir, 'jniLibs', androidABI
              ].join(File.separator)

            project.task('cleanNativeLibs', type: Delete) {
              delete "$stlDestination"
            }

            project.task('copyNativeLibs', type: Copy) {
              project.getProperty('ament.dependencies').split(':').each {
              def fp = [project.file(it).parentFile.parentFile, 'lib'
                ].join(File.separator)
              def ft = project.fileTree(dir: fp, include: '*.so')
              from ft
              into "$stlDestination"
              }

              // TODO(esteve): expand this to support other STL libraries
              def stlLibraryPath = [
                androidNDK, 'sources', 'cxx-stl', 'gnu-libstdc++', '4.9',
                'libs', androidABI, 'lib' + androidSTL + '.so'
              ].join(File.separator)

              from stlLibraryPath
            }

            project.copyNativeLibs.dependsOn project.cleanNativeLibs

            project.tasks.withType(JavaCompile) {
              compileTask -> compileTask.dependsOn project.copyNativeLibs
            }
          }

          project.assemble.finalizedBy project.deployArtifacts
        }

        project.dependencies {
          if (project.hasProperty('ament.dependencies')) {
            project.getProperty('ament.dependencies').split(':').each {
              compile project.fileTree(dir: [it, 'java'].join(File.separator),
                include: '*.jar')
            }
          }
        }
      }
    }
  }
}
