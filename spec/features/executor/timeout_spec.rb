require 'spec_helper'

feature 'Executor: Timeout' do
  include_context 'with live executor'

  scenario 'Fail Timeout task ends defective; Pass Timeout task passes' do
    trigger_job 'Timeout Demo'
    job_url = job_detail_url('timeout')

    wait_for_job_badge job_url, 'defective', timeout_sec: 120

    expect(page).to have_css '.badge', text: 'defective'
    expect(page).to have_css '.badge', text: 'passed'
  end
end
