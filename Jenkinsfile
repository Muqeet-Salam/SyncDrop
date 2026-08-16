pipeline {
    agent any

    stages {

        stage('Checkout') {
            steps {
                checkout scm
            }
        }

        stage('Web') {
            steps {
                echo 'Checking web application...'

                sh '''
                    test -f web/index.html
                    test -f web/style.css
                    test -f web/app.js
                '''
            }
        }

        stage('Android Build') {
            steps {
                echo 'Building Android application...'

                dir('android') {
                    sh './gradlew assembleDebug'
                }
            }
        }

        stage('Archive APK') {
            steps {
                archiveArtifacts artifacts: 'android/app/build/outputs/apk/debug/app-debug.apk',
                                fingerprint: true
            }
        }
    }

    post {
        success {
            echo 'SyncDrop CI completed successfully!'
        }

        failure {
            echo 'SyncDrop CI failed.'
        }
    }
}