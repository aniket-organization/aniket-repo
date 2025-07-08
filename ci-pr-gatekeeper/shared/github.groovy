import groovy.json.JsonSlurper

/**
 * Fetches repo-specific variables (like JIRA_PREFIX, LANGUAGE, etc.) from GitHub repo settings.
 */

def getGitHubCredentialsId() {
    def credId = env.GITHUB_CREDENTIALS_ID
    if (!credId) {
        error "❌ Missing environment variable: GITHUB_CREDENTIALS_ID"
    }
    return credId
}

def initializeRepositoryVariables() {
    def credentialsId = getGitHubCredentialsId()
    withCredentials([usernamePassword(credentialsId: credentialsId, usernameVariable: 'GITHUB_USER', passwordVariable: 'GITHUB_TOKEN_PSW')]) {
        script {
            echo "🔧 Fetching repository variables from GitHub for ${env.repo_full_name}"

            def apiUrl = "https://api.github.com/repos/${env.repo_owner}/${env.repo_name}/actions/variables"

            def response = sh(
                    script: """
                    set +x  
                    curl -s -u \"\$GITHUB_USER:\$GITHUB_TOKEN_PSW\" \\
                         -H \"Accept: application/vnd.github+json\" \\
                         \"${apiUrl}\"
                """,
                    returnStdout: true
            ).trim()

            if (!response) error "❌ Failed to fetch repo variables from GitHub."

            def json = new JsonSlurper().parseText(response)
            def vars = json.variables.collectEntries { [(it.name): it.value] }

            env.JIRA_PREFIX = vars.get('JIRA_PREFIX', env.DEFAULT_JIRA_PREFIX)
            env.CODE_COVERAGE_THRESHOLD = vars.get('CODE_COVERAGE_THRESHOLD', env.DEFAULT_CODE_COVERAGE_THRESHOLD)
            env.LANGUAGE = vars.get('LANGUAGE', env.DEFAULT_LANGUAGE).toLowerCase()
            env.SKIP_COMMIT_VALIDATION = vars.get('SKIP_COMMIT_VALIDATION', 'false').toBoolean()
        }
    }
}

/**
 * Determine and initialize build variables based on webhook/manual trigger.
 */
def initializeBuildVariables() {
    def repoName = (params.REPO_NAME ?: '').toString().trim()
    def prNumber = (params.PR_NUMBER ?: '').toString().trim()
    def buildType = (env.BUILD_TYPE ?: 'MANUAL').toUpperCase().trim()
    env.repoName = repoName
    env.prNumber = prNumber
    echo "🔧 Build Type: ${buildType}"

    if (!repoName) {
        error "❌ REPO_NAME parameter must be provided."
    }
    if (!prNumber) {
        error "❌ PR_NUMBER parameter must be provided."
    }
    if (!prNumber.isInteger()) {
        error "❌ PR_NUMBER must be a valid integer."
    }

    if (buildType == 'MANUAL') {
        echo "📋 Manual Build Detected → repo: ${repoName}, PR #: ${prNumber}"

        def credentialsId = getGitHubCredentialsId()
        withCredentials([usernamePassword(credentialsId: credentialsId, usernameVariable: 'GITHUB_USER', passwordVariable: 'GITHUB_TOKEN_PSW')]) {
            def apiUrl = "https://api.github.com/repos/pharmeasy/${repoName}/pulls/${prNumber}"
            def prData = sh(
                    script: """
                    set +x  
                    curl -s -u \"\$GITHUB_USER:\$GITHUB_TOKEN_PSW\" \\
                         -H \"Accept: application/vnd.github+json\" \\
                         \"${apiUrl}\"
                """,
                    returnStdout: true
            ).trim()

            def prJson = new JsonSlurper().parseText(prData)
            if (prJson.message == 'Not Found') {
                error "❌ PR #${prNumber} not found in repository ${repoName}. Please check the PR number and repository name."
            }

            env.pr_title        = prJson.title
            env.pr_sha          = prJson.head.sha
            env.pr_base_branch  = prJson.base.ref
            env.pr_head_branch  = prJson.head.ref
            env.repo_full_name  = prJson.head.repo.full_name
            env.pr_url          = prJson.html_url
            env.repo_owner      = prJson.head.repo.owner.login
            env.repo_name       = repoName
            env.pr_number       = prNumber
            env.pr_action       = 'manually triggered'
        }
    } else {
        echo "🔗 Webhook-triggered build using existing env variables"
    }
}

/**
 * Send GitHub status update (pending/success/failure).
 */
def sendGitHubStatus(String status, String description) {
    def commitId = env.pr_sha
    def apiUrl = "https://api.github.com/repos/${env.repo_owner}/${env.repo_name}/statuses/${commitId}"

    def credentialsId = getGitHubCredentialsId()
    withCredentials([usernamePassword(credentialsId: credentialsId, usernameVariable: 'GITHUB_USER', passwordVariable: 'GITHUB_TOKEN_PSW')]) {
        def httpStatus = sh(
                script: '''
                set +x
                curl -s -o /dev/null -w "%{http_code}" -X POST \\
                  -H "Authorization: Bearer ''' + GITHUB_TOKEN_PSW + '''" \\
                  -H "Content-Type: application/json" \\
                  -d '{
                    "state": "''' + status + '''",
                    "target_url": "''' + env.BUILD_URL + '''",
                    "description": "''' + description + '''",
                    "context": "ci/jenkins"
                  }' \\
                  "''' + apiUrl + '''"
            ''',
                returnStdout: true
        ).trim()

        if (httpStatus.startsWith('2')) {
            echo "✅ GitHub Checks status: ${status.toUpperCase()} updated"
        } else {
            echo "❌ Failed to update GitHub Checks Status: ${status.toUpperCase()} with HTTP CODE: ${httpStatus}"
        }
    }
}
return this
