require 'spec_helper'

feature 'Executor management UI' do

  before :each do
    @admin = FactoryBot.create(:admin)
    @user  = FactoryBot.create(:user)
  end

  context 'as an admin' do
    before :each do
      set_session_cookie @admin
      visit '/executors/'
    end

    scenario 'list page loads with heading, empty state, and Add Executor link' do
      expect(page).to have_content 'Executors'
      expect(page).to have_content 'No executors configured.'
      expect(page).to have_link 'Add Executor'
    end

    scenario 'add page shows executor form fields' do
      click_link 'Add Executor'
      expect(page).to have_content 'New'
      expect(page).to have_field 'Name'
      expect(page).to have_field 'Traits (comma-separated)'
      expect(page).to have_field 'Max load'
    end

    scenario 'creates an executor and shows one-time token on the list page' do
      click_link 'Add Executor'
      fill_in 'Name', with: 'build-box'
      fill_in 'Traits (comma-separated)', with: 'bash, ruby'
      fill_in 'Max load', with: '8'
      click_button 'Add Executor'

      expect(page).to have_content 'Executor created. Copy this token now'
      expect(page).to have_css 'code', text: /.{32,}/
      expect(page).to have_content 'build-box'
      expect(database[:executors].where(name: 'build-box').count).to eq 1
    end

    scenario 'token alert can be dismissed' do
      click_link 'Add Executor'
      fill_in 'Name', with: 'temp-executor'
      fill_in 'Max load', with: '4'
      click_button 'Add Executor'

      expect(page).to have_content 'Executor created. Copy this token now'
      click_on 'Dismiss'
      expect(page).not_to have_content 'Executor created. Copy this token now'
    end

    scenario 'created executor appears in list with enabled badge and traits' do
      click_link 'Add Executor'
      fill_in 'Name', with: 'detail-check'
      fill_in 'Traits (comma-separated)', with: 'bash'
      fill_in 'Max load', with: '2'
      click_button 'Add Executor'

      within('table') do
        expect(page).to have_content 'detail-check'
        expect(page).to have_content 'bash'
        expect(page).to have_content '2'
        expect(page).to have_css '.badge', text: 'enabled'
      end
    end

    scenario 'executor name links to detail page' do
      click_link 'Add Executor'
      fill_in 'Name', with: 'edit-me'
      fill_in 'Traits (comma-separated)', with: 'bash'
      fill_in 'Max load', with: '4'
      click_button 'Add Executor'

      click_on 'edit-me'

      expect(page).to have_css 'h2', text: 'edit-me'
      expect(page).to have_link 'Edit'
      expect(page).not_to have_field 'Name'
    end

    scenario 'detail page Edit button leads to pre-filled edit form' do
      click_link 'Add Executor'
      fill_in 'Name', with: 'edit-me'
      fill_in 'Traits (comma-separated)', with: 'bash'
      fill_in 'Max load', with: '4'
      click_button 'Add Executor'

      click_on 'edit-me'
      click_on 'Edit'

      expect(page).to have_field 'Name', with: 'edit-me'
      expect(page).to have_field 'Traits (comma-separated)', with: 'bash'
    end

    scenario 'can update executor name and traits from the edit page' do
      click_link 'Add Executor'
      fill_in 'Name', with: 'old-name'
      fill_in 'Traits (comma-separated)', with: 'bash'
      fill_in 'Max load', with: '4'
      click_button 'Add Executor'

      click_on 'old-name'
      click_on 'Edit'
      fill_in 'Name', with: 'new-name'
      fill_in 'Traits (comma-separated)', with: 'bash,ruby'
      click_button 'Save'

      expect(page).to have_css 'h2', text: 'new-name'
      row = database[:executors].where(name: 'new-name').first
      expect(row).not_to be_nil
      expect(row[:traits]).to include('ruby')
    end

    scenario 'can disable and re-enable an executor from the edit page' do
      click_link 'Add Executor'
      fill_in 'Name', with: 'toggle-exec'
      fill_in 'Max load', with: '4'
      click_button 'Add Executor'

      click_on 'toggle-exec'
      click_on 'Edit'

      click_on 'Disable'
      expect(page).to have_css '.badge', text: 'disabled'

      click_on 'Enable'
      expect(page).to have_css '.badge', text: 'enabled'
      expect(database[:executors].where(name: 'toggle-exec').first[:enabled]).to eq true
    end

    scenario 'can delete an executor from the edit page' do
      click_link 'Add Executor'
      fill_in 'Name', with: 'removable'
      fill_in 'Max load', with: '4'
      click_button 'Add Executor'

      click_on 'removable'
      click_on 'Edit'

      accept_confirm { click_on 'Delete' }

      expect(page).to have_content 'Executors'
      expect(page).to have_content 'No executors configured.'
      expect(database[:executors].where(name: 'removable').count).to eq 0
    end

    scenario 'trait filter narrows the executor list' do
      click_link 'Add Executor'
      fill_in 'Name', with: 'bash-only'
      fill_in 'Traits (comma-separated)', with: 'bash'
      fill_in 'Max load', with: '4'
      click_button 'Add Executor'

      click_link 'Add Executor'
      fill_in 'Name', with: 'ruby-only'
      fill_in 'Traits (comma-separated)', with: 'ruby'
      fill_in 'Max load', with: '4'
      click_button 'Add Executor'

      expect(page).to have_content 'bash-only'
      expect(page).to have_content 'ruby-only'

      fill_in 'Filter by traits (comma-separated)', with: 'bash'

      expect(page).to have_content 'bash-only'
      expect(page).not_to have_content 'ruby-only'
    end

    scenario 'trait filter updates URL as user types' do
      fill_in 'Filter by traits (comma-separated)', with: 'bash'
      expect(page.current_url).to include('traits=bash')

      find('button', text: '×').click
      expect(page.current_url).not_to include('traits=')
    end

    scenario 'trait filter URL param pre-fills the filter on load' do
      click_link 'Add Executor'
      fill_in 'Name', with: 'bash-box'
      fill_in 'Traits (comma-separated)', with: 'bash'
      fill_in 'Max load', with: '4'
      click_button 'Add Executor'

      visit '/executors/?traits=ruby'
      expect(find_field('Filter by traits (comma-separated)').value).to eq 'ruby'
      expect(page).not_to have_content 'bash-box'
    end
  end

  context 'as a non-admin user' do
    before :each do
      set_session_cookie @user
      visit '/executors/'
    end

    scenario 'list page is accessible and shows heading' do
      expect(page).to have_content 'Executors'
      expect(page).not_to have_content 'Request ERROR 403'
    end

    scenario 'Add Executor button is not shown' do
      expect(page).not_to have_link 'Add Executor'
    end

    scenario 'executor names link to detail page but Edit button is not shown' do
      set_session_cookie @admin
      visit '/executors/'
      click_link 'Add Executor'
      fill_in 'Name', with: 'view-only-box'
      fill_in 'Max load', with: '4'
      click_button 'Add Executor'

      set_session_cookie @user
      visit '/executors/'
      click_on 'view-only-box'

      expect(page).to have_css 'h2', text: 'view-only-box'
      expect(page).not_to have_link 'Edit'
    end
  end

end
