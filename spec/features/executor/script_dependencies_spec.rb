require 'spec_helper'

# End-to-end tests for Bucket B executor features:
#   - start_when: states: [executing]  (dispatch while dependency is in-flight)
#   - terminate_when                   (kill script when condition is met)

feature 'Script dependencies' do
  include_context 'with live executor'

  scenario 'termination task: to_be_terminated is killed when initial finishes' do
    trigger_job 'Script Dependencies'
    url = job_detail_url('scripts-dependencies')
    # Job has defective tasks (skip, skip-but-ignore) so the overall state is defective
    wait_for_job_badge(url, 'defective', timeout_sec: 120)

    # Find the Termination task row
    tr = first('td code', text: 'Termination', exact_text: true).ancestor('tr')
    # Task as a whole must be failed (initial passed, to_be_terminated killed)
    expect(tr.find('td:nth-child(2) .badge').text).to eq 'failed'

    # Navigate to the trial detail page to inspect per-script outcomes.
    # The job page shows only task-level state; script-level state lives on the trial page.
    trial_href = tr.find('td:nth-child(3) a.btn')['href']
    trial_url  = trial_href.start_with?('http') ? trial_href : "#{http_base_url}#{trial_href}"
    visit trial_url

    # Within the trial, verify individual script outcomes:
    #   initial          → passed  (sleep 10 runs to completion, exit 0)
    #   to_be_terminated → failed  (killed by terminate_when → non-zero exit)
    initial_tr = find('td code', text: 'initial', exact_text: true).ancestor('tr')
    expect(initial_tr.find('td:nth-child(2) .badge').text).to eq 'passed'

    terminated_tr = find('td code', text: 'to_be_terminated', exact_text: true).ancestor('tr')
    expect(terminated_tr.find('td:nth-child(2) .badge').text).to eq 'failed'
  end
end
