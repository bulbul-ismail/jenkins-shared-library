def call(body) {
    def pipelineParams= [:]
    body.resolveStrategy = Closure.DELEGATE_FIRST
    body.delegate = pipelineParams
    body()

def label = "worker-${UUID.randomUUID().toString()}"
def serviceAccount = "${env.SERVICE_ACCOUNT}"
def mavenImage = "${pipelineParams.MAVEN_IMAGE}"

podTemplate(
    label: label, 
    serviceAccount: serviceAccount,
    containers: [
        containerTemplate(name: 'maven', image: mavenImage, command: 'cat', ttyEnabled: true),
        containerTemplate(name: 'docker', image: 'docker', command: 'cat', ttyEnabled: true),
        containerTemplate(name: 'helm', image: 'lachlanevenson/k8s-helm:latest', command: 'cat', ttyEnabled: true)
    ],
    imagePullSecrets: ["${env.REGISTRY_SECRET}"],
    volumes: [
        hostPathVolume(mountPath: '/var/run/docker.sock', hostPath: '/var/run/docker.sock')
    ]
) {
    node(label) {
        stage('Checkout'){
            checkout scm
        }
        stage('Mvn Build'){
            container('maven'){
                sh "mvn clean install -DskipTests=true"
            }
        }
        stage('Docker build') {
            container('docker') {
               customImage = docker.build("${env.IMAGE_REGISTRY}/${env.IMAGE_REGISTRY_REPOSITORY_NAME}/${pipelineParams.PROJECT_NAME}:${env.BUILD_ID}")
            }
        }
        stage('Docker push') {
            container('docker') {
                docker.withRegistry("${env.IMAGE_REGISTRY_URL}", "${env.IMAGE_REGISTRY_CREDENTIAL_ID}") {
                    //customImage.push()
                }
            }
        }
        stage('Checkout Config Repo') {
            sh "mkdir conf"
            dir("conf"){
                checkout([$class: 'GitSCM', branches: [[name: "*/${env.CONFIG_REPO_BRANCH}"]], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'SparseCheckoutPaths', sparseCheckoutPaths: [[path: 'config/']]]], submoduleCfg: [], userRemoteConfigs: [[credentialsId: "${env.SCM_CREDENTIAL_ID}", url: "${env.CONFIG_REPO_URL}"]]])
            }
        }
        stage('Deploy') {
            container('helm') {
                sh "helm install --debug --dry-run --set-file properties.file.base=./conf/config/${pipelineParams.PROJECT_NAME}/${pipelineParams.PROJECT_NAME}.yml,properties.file.env=./conf/config/${pipelineParams.PROJECT_NAME}/${pipelineParams.PROJECT_NAME}-${env.ENVIRONMENT}.yml ${pipelineParams.PROJECT_NAME} ./${pipelineParams.HELM_CHART_NAME}"
            }
        }
    }
}
}
