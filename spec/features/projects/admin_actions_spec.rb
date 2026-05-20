require 'spec_helper'

feature 'Project admin actions' do

  before :each do
    @admin = FactoryBot.create(:admin)
    set_session_cookie @admin

    @project_id = 'demo-repo'
    database[:repositories].insert(
      id: @project_id,
      name: 'Demo Repository',
      git_url: 'https://example.test/demo.git'
    )
  end

  scenario 'admin can trigger a fetch' do
    visit "/projects/#{@project_id}"
    expect(page).to have_content 'Demo Repository'
    expect(page).to have_button 'Fetch now'
    click_button 'Fetch now'
    expect(current_path).to eq "/projects/#{@project_id}"
  end

  scenario 'admin can delete a project' do
    visit "/projects/#{@project_id}"
    expect(page).to have_content 'Demo Repository'
    click_button 'Delete project'
    wait_until(10) { current_path == '/projects/' }
    expect(database[:repositories].where(id: @project_id).count).to eq 0
  end

  scenario 'non-admin does not see admin actions' do
    user = FactoryBot.create(:user)
    set_session_cookie user

    visit "/projects/#{@project_id}"
    expect(page).to have_content 'Demo Repository'
    expect(page).not_to have_button 'Fetch now'
    expect(page).not_to have_button 'Delete project'
  end

end
