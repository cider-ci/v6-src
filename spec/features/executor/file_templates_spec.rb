require 'spec_helper'
require 'timeout'

# File templates (`templates:` in a task spec): src rendered with the trial's
# environment variables (ports included) into dest before any script runs; a
# missing source makes the trial defective without running scripts. The demo
# project's "Templates" job covers all cases.
feature 'Executor file templates' do
  include_context 'with live executor'

  scenario 'renders templates with env vars and ports; a missing template fails the task' do
    trigger_job 'Templates'
    url = job_detail_url('file_templates')
    # the job fails as a whole because of the deliberately missing template
    wait_for_job_badge(url, 'failed', timeout_sec: 180)

    states = database[:tasks].join(:jobs, id: :job_id)
                             .where(Sequel[:jobs][:key] => 'file_templates', Sequel[:jobs][:project_id] => project_id)
                             .select_hash(Sequel[:tasks][:name], Sequel[:tasks][:state])
    expect(states['Simple Template']).to eq 'passed'
    expect(states['Templates and Ports']).to eq 'passed'
    expect(states['Test Recursive Templating']).to eq 'passed'
    expect(%w[failed defective]).to include(states['Test Missing Template'])

    # the missing-template task never ran its script
    missing_task = database[:tasks].where(name: 'Test Missing Template').first
    trial = database[:trials].where(task_id: missing_task[:id]).order(:created_at).last
    expect(trial[:error].to_s).to include('does not exist')
  end
end
