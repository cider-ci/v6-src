require 'spec_helper'

# End-to-end tests for Bucket A executor features:
#   - script-level environment_variables merge
#   - template_environment_variables substitution
#   - exclusive_executor_resource with {{KEY}} template
#   - trial_attachments file collection from working dir

feature 'Executor feature parity' do
  include_context 'with live executor'

  scenario 'environment variables: script-level override and template substitution all pass' do
    # Runs the Environment Variables Demo job which tests:
    #   - task-level env var inheritance (SOME_DEFAULT=X)
    #   - script-level env var override (SOME_DEFAULT=Y in the script spec)
    #   - template_environment_variables: true ({{THE_ANSWER}} -> 42)
    #   - recursive substitution (T1->T2->T3, P1->P2->P3 via port)
    #   - template_environment_variables: false ({{...}} kept literal)
    trigger_job 'Environment Variables Demo'
    url = job_detail_url('environment_variables')
    wait_for_job_badge(url, 'passed', timeout_sec: 90)
    expect(page).to have_css '.badge', text: 'passed'
    # Verify the substitution sub-context tasks actually ran — they only exist
    # if map-style `contexts:` traversal and template_environment_variables work.
    %w[Enabled\ Substitution Recursive\ Substitution Disabled\ Substitution].each do |name|
      expect(page).to have_css('code', text: name)
    end
  end

  scenario 'trial attachments: executor uploads files matching include_match from working dir' do
    # The Timeout Demo's "fail" task times out after 5 s. Its main script writes
    # log.txt before sleeping; trial_attachments: logs: include_match: ^log\.txt$
    # means upload-trial-attachments! should pick up log.txt as "logs/log.txt".
    trigger_job 'Timeout Demo'
    url = job_detail_url('timeout')
    # Job is defective (fail task times out, pass task passes)
    wait_for_job_badge(url, 'defective', timeout_sec: 60)
    expect(database[:trial_attachments].where(path: 'logs/log.txt').count).to be >= 1
  end

  scenario 'exclusive executor resource with templated port: both tasks pass' do
    # The resource name "...{{TEST_PORT}}..." is resolved per-task, so the two
    # tasks get different resource names and can run their scripts in parallel.
    trigger_job 'Exclusive Executor Resource with Templated Port'
    url = job_detail_url('exclusive-executor-resource-with-templated-port')
    wait_for_job_badge(url, 'passed', timeout_sec: 120)
    expect(page).to have_css '.badge', text: 'passed'
  end
end
