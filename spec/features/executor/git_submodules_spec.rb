require 'spec_helper'
require 'timeout'

# git_options.submodules (include_match / exclude_match): submodules are
# checked out from the executor's per-URL bare-clone cache (legacy parity),
# not via `git submodule update` against the host for every trial. The demo
# project's "Submodule Demo" job covers: checked out, excluded, included by
# pattern, and the job without submodules.
feature 'Executor git submodules' do
  include_context 'with live executor'

  scenario 'checks submodules out according to include_match / exclude_match' do
    trigger_job 'Submodule Demo'
    url = job_detail_url('submodules')
    wait_for_job_badge(url, 'passed', timeout_sec: 240)

    states = database[:tasks].join(:jobs, id: :job_id)
                             .where(Sequel[:jobs][:key] => 'submodules', Sequel[:jobs][:project_id] => project_id)
                             .select_hash(Sequel[:tasks][:name], Sequel[:tasks][:state])
    expect(states.values.uniq).to eq ['passed']
    expect(states.keys).to include('Verify that README.md in the submodule does exist',
                                   'Verify that README.md in the submodule does not exist if clone is False',
                                   'Verify that README.md in the submodule does exist if include_match matches')
  end
end
