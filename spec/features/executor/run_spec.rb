require 'spec_helper'
require 'digest'
require 'securerandom'
require 'tempfile'
require 'timeout'

feature 'Executor Run' do

  let(:project_id)  { 'cider-ci-demo-project' }
  let(:head_commit) { 'eb15b2b3a521854ef2cb2cd8134fd3675f5053ec' }

  before :each do
    @admin = FactoryBot.create(:admin)
    set_session_cookie @admin

    database[:repositories].insert(
      id:      project_id,
      name:    'Demo Project',
      git_url: 'local'
    )

    @executor_token = SecureRandom.hex(32)
    database[:executors].insert(
      name:       'run-test-executor',
      token_hash: Digest::SHA256.hexdigest(@executor_token),
      token_part: @executor_token[0, 8],
      enabled:    true
    )

    project_dir = Pathname.new(__FILE__).join('../../../..').realdirpath
    @executor_log = Tempfile.new(['executor', '.log'])

    @executor_pid = Process.spawn(
      { 'CIDER_CI_EXECUTOR_TOKEN' => @executor_token,
        'CIDER_CI_SERVER_URL'     => http_base_url },
      project_dir.join('bin/executor-run').to_s,
      out: @executor_log.path,
      err: @executor_log.path
    )

    # Wait for the executor JVM + sync loop to be ready
    Timeout.timeout(40) do
      sleep 0.5 until File.read(@executor_log.path).include?('Executor sync loop starting')
    end
  end

  after :each do
    Process.kill('TERM', @executor_pid) rescue nil
    Process.wait(@executor_pid)         rescue nil
    @executor_log.unlink                rescue nil
  end

  scenario 'executor picks up and passes a trial end-to-end' do
    # Trigger a job via the browser
    visit "/projects/#{project_id}/commits/#{head_commit}/jobs"
    find('tr', text: 'Introduction Demo').find('button', text: 'Run').click
    expect(page).to have_content 'Recorded Jobs'

    # Extract job URL from the link (avoids scroll-into-view issues with <code> elements)
    job_url = find('a code', text: 'introduction-demo').ancestor('a')[:href]
    job_url = "#{http_base_url}#{job_url}" unless job_url.start_with?('http')

    # Poll the job page until the executor reports passed (up to 30 s)
    Timeout.timeout(30) do
      until page.has_css?('.badge', text: 'passed', minimum: 2, wait: 1)
        sleep 2
        visit job_url
      end
    end

    expect(page).to have_css '.badge', text: 'passed', minimum: 2

    # Verify that log attachments were uploaded for the trial(s)
    expect(page).to have_link 'Log'
    expect(database[:trial_attachments].where(path: 'log').count).to be >= 1
  end

end
