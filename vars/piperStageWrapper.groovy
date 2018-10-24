import com.sap.piper.Utils
import com.sap.piper.ConfigurationHelper
import com.sap.piper.ConfigurationLoader
import groovy.transform.Field

@Field String STEP_NAME = 'piperStageWrapper'

void call(Map parameters = [:], body) {

    def script = parameters.script ?: [commonPipelineEnvironment: commonPipelineEnvironment]
    def utils = parameters.juStabUtils ?: new Utils()

    def stageName = parameters.stageName?:env.STAGE_NAME

    // load default & individual configuration
    Map config = ConfigurationHelper
        .loadStepDefaults(this)
        .mixin(ConfigurationLoader.defaultStageConfiguration(this, stageName))
        .mixinGeneralConfig(script.commonPipelineEnvironment)
        .mixinStageConfig(script.commonPipelineEnvironment, stageName)
        .mixin(parameters)
        .use()

    stageLocking(config) {
        withNode(config) {
            try {
                utils.unstashAll(config.stashContent)

                if (Boolean.valueOf(env.ON_K8S) && containerMap.size() > 0) {
                    withEnv(["POD_NAME=${stageName}"]) {
                        dockerExecute(script: script, containerMap: containerMap) {
                            executeStage(body, stageName, config)
                        }
                    }
                } else {
                    executeStage(body, stageName, config)
                }
            } finally {
                deleteDir()
            }
        }
    }
}

String stageExitFilePath(String stageName, Map config) {
    return "${config.extensionLocation}${stageName.replace(' ', '_').toLowerCase()}.groovy"
}

String globalStageExitFilePath(String stageName, Map config) {
    return "${config.globalExtensionLocation}${stageName.replace(' ', '_').toLowerCase()}.groovy"
}

void stageLocking(Map config, Closure body) {
    if (config.stageLocking) {
        lock(resource: "${env.JOB_NAME}/${config.ordinal}", inversePrecedence: true) {
            milestone config.ordinal
            body()
        }
    } else {
        body()
    }
}

void withNode(Map config, Closure body) {
    if (config.withNode) {
        node(config.nodeLabel) {
            body()
        }
    } else {
        body()
    }
}

void executeStage(originalStage, stageName, config) {

    /* Defining the sources where to look for a project extension and a repository extension.
    * Files need to be named like the executed stage to be recognized.
    */
    def projectInterceptorFile = stageExitFilePath(stageName, config)
    def globalInterceptorFile = globalStageExitFilePath(stageName, config)
    boolean projectExtensions = fileExists(projectInterceptorFile)
    boolean globalExtensions = fileExists(globalInterceptorFile)
    // Pre-defining the real originalStage in body variable, might be overwritten later if extensions exist
    def body = originalStage

    if (globalExtensions) {
        Script globalInterceptorScript = load(globalInterceptorFile)
        echo "[${STEP_NAME}] Found global interceptor '${globalInterceptorFile}' for ${stageName}."
        // If we call the global interceptor, we will pass on originalStage as parameter
        body = {
            // passing config twice to keep compatibility with https://github.com/SAP/cloud-s4-sdk-pipeline-lib/blob/master/vars/runAsStage.groovy
            globalInterceptorScript(body, stageName, config, config)
        }
    }

    if (projectExtensions) {
        Script projectInterceptorScript = load(projectInterceptorFile)
        echo "[${STEP_NAME}] Running project interceptor '${projectInterceptorFile}' for ${stageName}."
        // If we call the project interceptor, we will pass on body as parameter which contains either originalStage or the repository interceptor
        // passing config twice to keep compatibility with https://github.com/SAP/cloud-s4-sdk-pipeline-lib/blob/master/vars/runAsStage.groovy
        projectInterceptorScript(body, stageName, config, config)
    } else {
        //TODO: assign projectInterceptorScript to body as done for globalInterceptorScript, currently test framework does not seem to support this case. Further investigations needed.
        body()
    }

}