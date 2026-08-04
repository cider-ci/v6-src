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
    # Task as a whole must be failed (initial passed, to_be_terminated failed)
    expect(tr.find('td:nth-child(2) .badge').text).to eq 'failed'

    # Within the trial, verify individual script outcomes:
    #   initial      → passed  (ran to completion: sleep 10 exits 0)
    #   to_be_terminated → failed  (killed by terminate_when → non-zero exit → failed)
    initial_row = tr.find('.mt-1.ms-3 code', text: 'initial', exact_text: true).ancestor('.mt-1.ms-3')
    expect(initial_row).to have_css('.badge', text: 'passed')

    terminated_row = tr.find('.mt-1.ms-3 code', text: 'to_be_terminated', exact_text: true).ancestor('.mt-1.ms-3')
    expect(terminated_row).to have_css('.badge', text: 'failed')
  end
end
