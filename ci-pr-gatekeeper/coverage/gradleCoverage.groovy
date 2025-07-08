def runCoverageCheck(threshold, baseDir = '.') {
    dir(baseDir) {
        sh './gradlew test jacocoTestReport --build-cache --parallel'

        // Run diff-cover only if not already installed
        sh '''
        if ! command -v diff-cover > /dev/null; then
          echo "📦 Installing diff-cover..."
          python3 -m pip install --user diff-cover
        fi
        '''

        // Git fetch optimized
        def fileExtension = env.language == 'kotlin' ? 'kt' : 'java'
        def coverageOutput = sh(script: """
            git fetch origin ${env.pr_base_branch}
            diff-cover build/reports/jacoco/test/jacocoTestReport.xml \\
              --compare-branch origin/${env.pr_base_branch} \\
              --src-roots src/main/${env.language} \\
              --include "**/service/**/*.${fileExtension}" \\
              --fail-under ${threshold}
        """, returnStdout: true)

        echo coverageOutput
        echo "✅ PR diff coverage is above ${threshold}%"
    }
}

return this
