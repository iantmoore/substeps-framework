/*
 *  Copyright Technophobia Ltd 2012
 *
 *   This file is part of Substeps Maven Runner.
 *
 *    Substeps Maven Runner is free software: you can redistribute it and/or modify
 *    it under the terms of the GNU Lesser General Public License as published by
 *    the Free Software Foundation, either version 3 of the License, or
 *    (at your option) any later version.
 *
 *    Substeps Maven Runner is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *    GNU Lesser General Public License for more details.
 *
 *    You should have received a copy of the GNU Lesser General Public License
 *    along with Substeps.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.technophobia.substeps.runner;

import com.typesafe.config.Config;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.*;
import org.substeps.report.IReportBuilder;
import org.substeps.runner.NewSubstepsExecutionConfig;

import java.io.File;
import java.util.List;

/**
 * Mojo to run a number SubStep features, each contained within any number of
 * executionConfigs, encapsulating the required config and setup and tear down
 * details
 */
@Mojo(name = "build-report",
        defaultPhase = LifecyclePhase.VERIFY,
        requiresDependencyResolution = ResolutionScope.TEST,
        requiresProject = true,
        configurator = "include-project-dependencies")
public class SubstepsReportBuilderMojo extends BaseSubstepsMojo {


    @Override
    public void executeConfig(Config cfg)  {
        // no op
    }

    @Override
    public void executeBeforeAllConfigs(Config masterConfig) {
        // no op
    }

    @Override
    public void executeAfterAllConfigs(Config masterConfig) throws MojoFailureException{

        setupBuildEnvironmentInfo();


        IReportBuilder localReportBuilder = NewSubstepsExecutionConfig.getReportBuilder(masterConfig);

        File rootDataDir = NewSubstepsExecutionConfig.getRootDataDir(masterConfig);
        File stepImplsJsonFile = new File(outputDirectory, STEP_IMPLS_JSON_FILENAME);

        File reportDir = NewSubstepsExecutionConfig.getReportDir(masterConfig);

        // "src/test/resources/sample-results-data"
        localReportBuilder.buildFromDirectory(rootDataDir, reportDir, stepImplsJsonFile);

        List<Throwable> exceptions = this.session.getResult().getExceptions();

        if (exceptions != null && !exceptions.isEmpty()){
            getLog().info("got exceptions");
            for (Throwable t : exceptions){
                if (t instanceof MojoFailureException){
                    MojoFailureException failure = (MojoFailureException)t;
                    // remove the exception otherwise it will get logged twice
                    this.session.getResult().getExceptions().remove(t);

                    throw failure;
                }
            }
        }
        else {
            getLog().info("All good, no failures");
        }
    }
}
