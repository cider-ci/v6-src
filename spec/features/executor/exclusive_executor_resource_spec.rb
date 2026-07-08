require 'spec_helper'

feature 'Executor: Exclusive Executor Resource' do
  include_context 'with live executor'

  scenario 'all tasks pass (exclusive_executor_resource enforces serial access)' do
    trigger_job 'Exclusive Executor Resource'
    job_url = job_detail_url('exclusive-executor-resource')

    wait_for_job_badge job_url, 'passed', timeout_sec: 120

    expect(page).to have_css '.badge', text: 'passed', minimum: 2
  end
end
