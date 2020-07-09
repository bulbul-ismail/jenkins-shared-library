def call(body) {
    def pipelineParams= [:]
    body.resolveStrategy = Closure.DELEGATE_FIRST
    body.delegate = pipelineParams
    body()

def label = "worker-${UUID.randomUUID().toString()}"
def serviceAccount = "${env.SERVICE_ACCOUNT}"
def mavenImage = "${pipelineParams.MAVEN_IMAGE}"
def subDirectory = "${pipelineParams.SUB_DIRECTORY}"

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
                dir(subDirectory){
                    sh "mvn clean install -DskipTests=true"
                }
            }
        }
        stage('Docker build') {
            container('docker') {
                dir(subDirectory){
                    customImage = docker.build("${env.IMAGE_REGISTRY}/${env.IMAGE_REGISTRY_REPOSITORY_NAME}/${pipelineParams.PROJECT_NAME}:${env.BUILD_ID}")
                }
            }
        }
        stage('Docker push') {
            container('docker') {
                dir(subDirectory){
                    docker.withRegistry("${env.IMAGE_REGISTRY_URL}", "${env.IMAGE_REGISTRY_CREDENTIAL_ID}") {
                        customImage.push()
                    }
                }
            }
        }
        stage('Deploy') {
            container('helm') {
                dir(subDirectory){
                    sh "helm install --debug --dry-run ${pipelineParams.PROJECT_NAME} ./${pipelineParams.HELM_CHART_NAME}"
                }
            }
        }
    }
}
}
