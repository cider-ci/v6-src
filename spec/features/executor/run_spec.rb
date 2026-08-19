require 'spec_helper'
require 'digest'
require 'fileutils'
require 'securerandom'
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
    log_dir = project_dir.join('tmp/executor-logs')
    FileUtils.mkdir_p(log_dir)
    @executor_log_path = log_dir.join("executor-#{SecureRandom.hex(6)}.log").to_s

    @executor_pid = Process.spawn(
      { 'CIDER_CI_EXECUTOR_TOKEN' => @executor_token,
        'CIDER_CI_SERVER_URL'     => http_base_url },
      project_dir.join('bin/executor-run').to_s,
      out: @executor_log_path,
      err: @executor_log_path
    )

    # Wait for the executor JVM + sync loop to be ready
    Timeout.timeout(40) do
      sleep 0.5 until File.read(@executor_log_path).include?('Executor sync loop starting')
    end
  end

  after :each do
    Process.kill('TERM', @executor_pid) rescue nil
    Process.wait(@executor_pid)         rescue nil
  end

  scenario 'executor picks up and passes a trial end-to-end' do
    # Trigger a job via the browser
    visit "/projects/#{project_id}/commits/#{head_commit}/jobs"
    find('tr', text: 'Introduction Demo').find('button', text: 'Run').click
    expect(page).to have_content 'Recorded Jobs'

    # Extract job URL from the link (avoids scroll-into-view issues with <code> elements)
    job_url = find('a code', text: 'introduction-demo').ancestor('a')[:href]
    job_url = "#{http_base_url}#{job_url}" unless job_url.start_with?('http')

    # Poll the job page until the executor reports passed (up to 120 s)
    begin
      Timeout.timeout(120) do
        until page.has_css?('h3 .badge', text: 'passed', wait: 1)
          sleep 2
          visit job_url
        end
      end
    rescue Timeout::Error
      warn "=== run_spec timeout: DB state ==="
      warn database[:jobs].select(:key, :state).all.inspect
      warn database[:tasks].select(:name, :state).all.inspect
      warn database[:trials].select(:state, :error, :dispatched_at).all.inspect
      warn "=== last 60 lines of executor log ==="
      warn File.read(@executor_log_path).lines.last(60).join rescue nil
      raise
    end

    expect(page).to have_css 'h3 .badge', text: 'passed'

    # Verify that log attachments were uploaded for the trial(s)
    expect(database[:trial_attachments].where(path: 'scripts/main').count).to be >= 1
  end

end
