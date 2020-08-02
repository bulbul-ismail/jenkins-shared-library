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
        hostPathVolume(mountPath: '/var/run/docker.sock', hostPath: '/var/run/docker.sock'),
        persistentVolumeClaim(claimName: 'maven-claim', mountPath: '/tmp/maven')
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
        stage('Deploy') {
            container('helm') {
                sh "helm repo add jfrog-repo ${env.HELM_REPO_URL} --username ${env.HELM_REPO_USERNAME} --password ${env.HELM_REPO_PASSWORD}"
                sh "helm repo update"
                sh "helm upgrade --install ${pipelineParams.PROJECT_NAME} ${pipelineParams.HELM_CHART_NAME} --version ${pipelineParams.HELM_CHART_VERSION} -f values.yaml --set image.tag=${env.BUILD_ID}"
            }
        }
    }
}
}
