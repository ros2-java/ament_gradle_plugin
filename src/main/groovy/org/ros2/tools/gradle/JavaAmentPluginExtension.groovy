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
import org.gradle.api.ProjectEvaluationListener
import org.gradle.api.ProjectState
import org.gradle.api.artifacts.DependencyResolutionListener
import org.gradle.api.artifacts.ResolvableDependencies
import org.gradle.api.tasks.compile.AbstractCompile
import org.gradle.jvm.application.tasks.CreateStartScripts
import org.gradle.api.internal.plugins.StartScriptGenerator

class AmentEntryPoints {
  final project
  def consoleScripts

  AmentEntryPoints(project) {
    this.project = project
  }
}

class JavaAmentPluginExtension extends BaseAmentPluginExtension {

  def scriptEngine
  def templateText

  JavaAmentPluginExtension(project) {
    super(project)
  }

  def updateJavaOutput() {
    project.jar {
      destinationDir = project.file(
        [this.installSpace, 'share',
          this.packageManifestName,
          'java'].join(File.separator)
      )
    }
  }

  def updateJavaDependencies() {
    def compileDeps = project.getConfigurations().getByName('implementation').getDependencies()
    def fileDeps = project.files(project.ament.dependencies.split(':').collect {
      project.fileTree(dir: [it, 'java'].join(File.separator), include: '*.jar')
    })
    compileDeps.add(project.getDependencies().create(fileDeps))
  }

  def setup() {
    project.plugins.withType(org.gradle.api.plugins.JavaPlugin) {
      project.getGradle().addListener(new DependencyResolutionListener() {
          @Override
          void beforeResolve(ResolvableDependencies resolvableDependencies) {
            updateJavaDependencies()
            updateJavaOutput()
            project.getGradle().removeListener(this)
          }

          @Override
          void afterResolve(ResolvableDependencies resolvableDependencies) {}
      })

      project.getGradle().addListener(new ProjectEvaluationListener() {
        // We will rewrite the CLASSPATH variable in the script and rely on the environment
        // variable instead
        def classpathFix(script) {
          def lines = script.readLines().collect {
            if (it ==~ /^(set )?CLASSPATH=/) {
              return ''
            } else {
              return it
            }
          }
          script.withPrintWriter { writer -> lines.each { writer.println it } }
        }

        def createDeployRunnerScriptsTask(proj) {
          def deployRunnerScriptsTask = proj.task('deployRunnerScripts')
          proj.ament.entryPoints.consoleScripts.each {
            def (runner, className) = it.trim().split(' = ')

            def task = proj.task("createStartScripts__${runner}", type: CreateStartScripts) {
              outputDir = project.file(
                [proj.ament.installSpace, 'lib',
                 proj.ament.packageManifestName].join(File.separator))
              mainClassName = className
              applicationName = runner
              classpath = proj.files([])
            }

            task.doLast {
              classpathFix(unixScript)
              classpathFix(windowsScript)
            }

            deployRunnerScriptsTask.dependsOn task
          }
          return deployRunnerScriptsTask
        }

        @Override
        void afterEvaluate(Project proj, ProjectState state) {
          def deployRunnerScriptsTask = createDeployRunnerScriptsTask(proj)
          project.assemble.finalizedBy deployRunnerScriptsTask
        }

        @Override
        void beforeEvaluate(Project proj) {}
      })

      def checkAmentPropertiesTask = createCheckAmentPropertiesTask()

      def storeAmentPropertiesTask = createStoreAmentPropertiesTask()
      storeAmentPropertiesTask.dependsOn checkAmentPropertiesTask

      project.tasks.withType(AbstractCompile) {
        compileTask -> compileTask.dependsOn storeAmentPropertiesTask
      }
    }
  }
}
