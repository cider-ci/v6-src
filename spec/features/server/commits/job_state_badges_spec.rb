require 'spec_helper'
require 'securerandom'

# The commits page shows, as first column, one count badge per job state
# group (failed incl. defective/aborted, passed, executing, pending) instead
# of a badge per job; each links to the commit's jobs page filtered by that
# group (legacy parity).
feature 'Commits page job state badges' do

  BADGES_PROJECT_ID  = 'cider-ci-demo-project'
  BADGES_HEAD_COMMIT = 'eb15b2b3a521854ef2cb2cd8134fd3675f5053ec'

  before :each do
    @admin = FactoryBot.create(:admin)
    set_session_cookie @admin
    database[:repositories].insert(id: BADGES_PROJECT_ID, name: 'Demo Project', git_url: 'local',
                                   branch_trigger_include_match: '^__none__$')
    database[:commits].insert(id: BADGES_HEAD_COMMIT, tree_id: 'f' * 40, subject: 'Badges commit',
                              author_name: 'A', committer_name: 'A', committer_date: Time.now.utc)
    database[:branches].insert(id: SecureRandom.uuid, repository_id: BADGES_PROJECT_ID,
                               name: 'master', current_commit_id: BADGES_HEAD_COMMIT)
    { 'j-passed-1' => 'passed', 'j-passed-2' => 'passed', 'j-failed' => 'failed',
      'j-aborted' => 'aborted', 'j-defective' => 'defective', 'j-exec' => 'executing',
      'j-pending' => 'pending' }.each do |key, state|
      database[:jobs].insert(project_id: BADGES_PROJECT_ID, commit_id: BADGES_HEAD_COMMIT,
                             key: key, name: key, state: state)
    end
  end

  scenario 'shows counts per state group as the first column, commit id second' do
    visit '/commits/'
    row = find('tr', text: 'Badges commit')
    expect(row.all('td')[0][:class]).to include('jobs-summary')
    expect(row.all('td')[1]).to have_css('code', text: BADGES_HEAD_COMMIT[0, 7])
    within(row.find('td.jobs-summary')) do
      expect(find('a[data-state="failed"]').text).to eq '3'     # failed + aborted + defective
      expect(find('a[data-state="passed"]').text).to eq '2'
      expect(find('a[data-state="executing"]').text).to eq '1'
      expect(find('a[data-state="pending"]').text).to eq '1'
      expect(page).to have_css('a.badge', count: 4)
    end
  end

  scenario 'a badge leads to the jobs page filtered by that state group' do
    visit '/commits/'
    find('tr', text: 'Badges commit').find('td.jobs-summary a[data-state="failed"]').click
    expect(page).to have_current_path(%r{/projects/#{BADGES_PROJECT_ID}/commits/#{BADGES_HEAD_COMMIT}/jobs\?state=failed})
    expect(page).to have_css('.recorded-jobs-filter', text: '3 failed of 7')
    expect(all('table.recorded-jobs tbody tr').map { |r| r.first('code').text }.sort).to eq %w[j-aborted j-defective j-failed]
    within('.recorded-jobs-filter') { click_link 'show all' }
    expect(page).to have_css('table.recorded-jobs tbody tr', count: 7)
  end
end
